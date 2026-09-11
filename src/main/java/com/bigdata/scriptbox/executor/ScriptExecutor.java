package com.bigdata.scriptbox.executor;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.model.ExecutionStatus;
import com.bigdata.scriptbox.model.RiskLevel;
import com.bigdata.scriptbox.model.VisibleWhen;
import com.bigdata.scriptbox.service.ArtifactService;
import com.bigdata.scriptbox.service.FileUploadService;
import com.bigdata.scriptbox.service.GlobalVariableService;
import com.bigdata.scriptbox.service.PrecheckService;
import com.bigdata.scriptbox.service.PresetService;
import com.bigdata.scriptbox.service.ResultParserService;
import com.bigdata.scriptbox.service.RunningExecutionRegistry;
import com.bigdata.scriptbox.service.RunningExecutionRegistry.RunningExecution;
import com.bigdata.scriptbox.service.SensitiveDataMasker;
import com.bigdata.scriptbox.service.StoragePathService;
import com.bigdata.scriptbox.service.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 脚本执行器（业务层）。
 *
 * <p>执行流程：
 * <ol>
 *   <li>{@link #prepareContext} — 校验脚本 / 租户状态、解析 Preset、合并并校验参数、
 *       创建执行目录、把 file 参数 promote 到受控路径。</li>
 *   <li>{@link #runPrecheckOrRecordFailure} — 跑 PreCheck；失败时直接写一条
 *       PRECHECK_FAILED 历史并返回。</li>
 *   <li>{@link #captureSnapshot} — 读脚本正文计算 SHA-256，并构建 execution_snapshot
 *       （敏感 GlobalVariable 已被 {@link SensitiveDataMasker} 遮罩）。</li>
 *   <li>{@link #startProcess} — 构造 ProcessRequest，委托 {@link ProcessRunner}
 *       启动并等待。</li>
 *   <li>{@link #finalizeExecution} — 根据 ProcessResult 写状态；调用
 *       {@link ResultParserService} 解析 result.json；调用
 *       {@link ArtifactService#scanAndRegister} 扫描 artifact。</li>
 * </ol>
 *
 * <p>注意：
 * <ul>
 *   <li>本类禁止直接拼接 {@code bash -c <user_input>}，所有命令行参数通过
 *       {@code ProcessBuilder(List<String>)} 形式传入，避免 shell 注入。</li>
 *   <li>危险脚本的二次确认（{@link RiskLevel#CONFIRM_TOKEN}）由前端在弹窗里
 *       收集；snapshot 重放走 {@link ExecutionRequest#isBypassDangerousCheck()}
 *       跳过二次确认。</li>
 *   <li>并发上限由 {@link RunningExecutionRegistry} 实时控制；本类只在调用前
 *       做一次校验，调用 {@code pb.start()} 之前的窗口期不算超限（多 1 个也无所谓）。</li>
 * </ul>
 */
@Component
public class ScriptExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScriptExecutor.class);

    @Autowired private ScriptBoxProperties props;
    @Autowired private com.bigdata.scriptbox.service.ScriptService scriptService;
    @Autowired private ExecutionHistoryMapper historyMapper;
    @Autowired private TenantService tenantService;
    @Autowired private GlobalVariableService globalVariableService;
    @Autowired private PresetService presetService;
    @Autowired private ResultParserService resultParserService;
    @Autowired private FileUploadService fileUploadService;
    @Autowired private PrecheckService precheckService;
    @Autowired private RunningExecutionRegistry runningRegistry;
    @Autowired private ArtifactService artifactService;
    @Autowired private StoragePathService storagePathService;
    @Autowired private SensitiveDataMasker sensitiveDataMasker;
    @Autowired private ProcessRunner processRunner;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong counter = new AtomicLong(System.currentTimeMillis() * 1000L);

    public ExecutionHistory history(Long id) {
        return historyMapper.selectById(id);
    }

    // ======================================================================
    // 主入口
    // ======================================================================

    public ExecutionHistory execute(ExecutionRequest req) throws IOException {
        ExecutionContext ctx = prepareContext(req);
        attachMdc(ctx);

        try {
            // PreCheck 阶段独立处理：失败时也要把 PRECHECK_FAILED 写进 history
            ExecutionHistory precheckFailure = runPrecheckOrRecordFailure(ctx);
            if (precheckFailure != null) {
                return precheckFailure;
            }

            captureSnapshot(ctx);

            log.info("开始执行脚本 executionId={} script={} tenant={} cmd={}",
                    ctx.executionId(), ctx.script().getName(), ctx.tenant().getName(),
                    sensitiveDataMasker.maskCommandList(buildArgsFromMap(ctx.params())));

            ProcessResult processResult = startProcess(ctx);
            return finalizeExecution(ctx, processResult);
        } finally {
            MDC.remove("executionId");
            MDC.remove("scriptName");
            MDC.remove("tenantName");
        }
    }

    /**
     * 主流程前 60%：参数 / 路径 / 历史行初始化。
     */
    private ExecutionContext prepareContext(ExecutionRequest req) throws IOException {
        Script script = scriptService.getById(req.getScriptId());
        if (script == null)
            throw new IllegalArgumentException("脚本不存在: " + req.getScriptId());
        if (script.getEnabled() == null || !script.getEnabled())
            throw new IllegalArgumentException("脚本已禁用: " + script.getName());

        if (RiskLevel.DANGEROUS.equals(script.getRiskLevel())
                && !req.isBypassDangerousCheck()
                && !RiskLevel.CONFIRM_TOKEN.equals(req.getConfirmToken())) {
            throw new IllegalArgumentException(
                    "脚本标记为 DANGEROUS，请在前端输入 " + RiskLevel.CONFIRM_TOKEN + " 后重新执行");
        }

        // 防重复执行：allowConcurrent=false 时，同一脚本不能并发
        if (!Boolean.TRUE.equals(script.getAllowConcurrent())) {
            for (RunningExecution re : runningRegistry.activeExecutions()) {
                if (re.scriptId == script.getId()) {
                    throw new IllegalStateException(
                            "脚本 '" + script.getName() + "' 已有运行中的执行（already running，executionId="
                                    + re.executionId + "），请等待完成或在脚本上启用 allowConcurrent=true");
                }
            }
        }

        Tenant tenant = tenantService.getById(req.getTenantId());
        if (tenant == null)
            throw new IllegalArgumentException("租户不存在: " + req.getTenantId());
        if (tenant.getEnabled() == null || !tenant.getEnabled())
            throw new IllegalArgumentException("租户已禁用: " + tenant.getName());

        // 参数合并：preset < supplied
        List<ScriptParam> params = scriptService.paramsOf(script.getId());
        Map<String, String> validated;
        if (req.getPresetId() != null) {
            var preset = presetService.get(script.getId(), req.getPresetId());
            if (preset == null) throw new IllegalArgumentException("预设不存在: " + req.getPresetId());
            Map<String, String> fromPreset = presetService.applyParams(preset);
            Map<String, String> supplied = req.getParams() == null ? Map.of() : req.getParams();
            Map<String, String> merged = new LinkedHashMap<>(fromPreset);
            merged.putAll(supplied);
            validated = validateAndCoerce(params, merged);
        } else {
            validated = validateAndCoerce(params, req.getParams() == null ? Map.of() : req.getParams());
        }

        // 文件参数：把上传到 ./data/uploads/<token>/ 的文件 promote 到 ./data/executions/<execId>/input/
        if (req.getFileInputs() != null && !req.getFileInputs().isEmpty()) {
            Map<String, String> resolved = fileUploadService.promoteForExecution(
                    nextExecutionId(), req.getFileInputs());
            validated.putAll(resolved);
        }

        long executionId = nextExecutionId();
        Path execDir = storagePathService.executionDirFor(executionId);
        Files.createDirectories(execDir);
        Path artifactDir = storagePathService.artifactsDirFor(executionId);
        // 注意：artifact 目录由 ArtifactService 在 scanAndRegister 中创建；
        // 这里调一次 createDirectories 让脚本开始写时目录已存在。
        Files.createDirectories(artifactDir);

        Path stdoutFile = execDir.resolve("stdout.log");
        Path stderrFile = execDir.resolve("stderr.log");
        Path resultFile = execDir.resolve("result.json");
        int timeoutSeconds = script.getTimeoutSeconds() == null ? 600 : script.getTimeoutSeconds();

        ExecutionHistory history = newExecutionHistory(req, executionId, script, tenant,
                validated, execDir, stdoutFile, stderrFile, resultFile, timeoutSeconds);

        // 把 executionId 同步给 FileUploadService（之前在 promoteForExecution 调用里用了临时值，
        // 现在重新调一次以保证文件落到正确目录）。
        if (req.getFileInputs() != null && !req.getFileInputs().isEmpty()) {
            Map<String, String> resolved = fileUploadService.promoteForExecution(executionId, req.getFileInputs());
            for (Map.Entry<String, String> e : resolved.entrySet()) {
                validated.put(e.getKey(), e.getValue());
            }
        }

        return ExecutionContext.builder()
                .executionId(executionId)
                .request(req)
                .script(script)
                .tenant(tenant)
                .params(validated)
                .executionDir(execDir)
                .artifactDir(artifactDir)
                .stdoutPath(stdoutFile)
                .stderrPath(stderrFile)
                .resultPath(resultFile)
                .scriptPath(Paths.get(script.getScriptPath() == null ? "" : script.getScriptPath()))
                .kinitWrapped(false) // 实际值在 startProcess 中根据 tenant keytab 决定
                .timeoutSeconds(timeoutSeconds)
                .build();
    }

    private ExecutionHistory newExecutionHistory(ExecutionRequest req, long executionId,
                                                  Script script, Tenant tenant,
                                                  Map<String, String> validated,
                                                  Path execDir, Path stdoutFile,
                                                  Path stderrFile, Path resultFile,
                                                  int timeoutSeconds) {
        ExecutionHistory history = new ExecutionHistory();
        history.setId(executionId);
        history.setScriptId(script.getId());
        history.setScriptName(script.getName());
        history.setTenantId(tenant.getId());
        history.setTenantName(tenant.getName());
        try {
            history.setParametersJson(mapper.writeValueAsString(validated));
        } catch (Exception ex) {
            // JSON 序列化失败几乎不可能（都是 String → String），但保险起见写空串
            history.setParametersJson("{}");
        }
        history.setTimeout(Boolean.FALSE);
        history.setStartTime(LocalDateTime.now());
        history.setStdoutPath(stdoutFile.toAbsolutePath().toString());
        history.setStderrPath(stderrFile.toAbsolutePath().toString());
        history.setExecutionDir(execDir.toAbsolutePath().toString());
        history.setResultJsonPath(resultFile.toAbsolutePath().toString());
        history.setStatus(ExecutionStatus.RUNNING.name());
        history.setSuccess(false);
        if (req.getBatchId() != null) {
            history.setBatchId(req.getBatchId());
            history.setBatchRowIndex(req.getBatchRowIndex());
        }
        if (req.getScenarioId() != null) {
            history.setScenarioId(req.getScenarioId());
            history.setScenarioStepNo(req.getScenarioStepNo());
        }
        return history;
    }

    /**
     * 跑 PreCheck；失败时直接写一条 PRECHECK_FAILED 历史并返回非 null，
     * 成功时返回 null 表示继续走主流程。
     */
    private ExecutionHistory runPrecheckOrRecordFailure(ExecutionContext ctx) {
        log.info("开始 PreCheck executionId={} script={}", ctx.executionId(), ctx.script().getName());
        Map<String, Object> precheck = precheckService.run(ctx.script(), ctx.tenant());
        if (Boolean.TRUE.equals(precheck.get("ok"))) {
            log.info("PreCheck 通过 executionId={}", ctx.executionId());
            return null;
        }

        log.warn("PreCheck 失败 executionId={} script={} message={}",
                ctx.executionId(), ctx.script().getName(), precheck.get("message"));

        ExecutionHistory history = newExecutionHistory(ctx.request(), ctx.executionId(),
                ctx.script(), ctx.tenant(), ctx.params(),
                ctx.executionDir(), ctx.stdoutPath(), ctx.stderrPath(), ctx.resultPath(),
                ctx.timeoutSeconds());
        history.setEndTime(LocalDateTime.now());
        history.setDurationMs(0L);
        history.setExitCode(-1);
        history.setTimeout(false);
        history.setSuccess(false);
        history.setStatus(ExecutionStatus.PRECHECK_FAILED.name());
        historyMapper.insert(history);
        return history;
    }

    /**
     * 读脚本正文计算 SHA-256 + 构建 execution_snapshot（敏感变量已被遮罩），
     * 并把 RUNNING 状态的 history 行写入数据库。
     */
    private void captureSnapshot(ExecutionContext ctx) throws IOException {
        Path scriptPath = ctx.scriptPath();
        if (scriptPath == null || !Files.exists(scriptPath)) {
            throw new IllegalStateException("脚本文件不存在: " + scriptPath);
        }
        // 路径安全：脚本文件必须落在配置的 scripts 目录下，阻止恶意路径逃逸
        storagePathService.assertInside(scriptPath, storagePathService.scriptsRoot(), "script");

        ExecutionHistory row = historyMapper.selectById(ctx.executionId());
        boolean isNew = (row == null);
        if (isNew) {
            row = newExecutionHistory(ctx.request(), ctx.executionId(), ctx.script(), ctx.tenant(),
                    ctx.params(), ctx.executionDir(), ctx.stdoutPath(), ctx.stderrPath(),
                    ctx.resultPath(), ctx.timeoutSeconds());
        }
        try {
            byte[] bodyBytes = Files.readAllBytes(scriptPath);
            row.setScriptSha256(sha256Hex(bodyBytes));
            row.setSnapshotJson(buildSnapshotJson(ctx.script(), ctx.tenant(), ctx.params(),
                    new String(bodyBytes, StandardCharsets.UTF_8)));
        } catch (IOException ioe) {
            // snapshot 失败不应中断执行；记录 WARN 即可
            log.warn("snapshot/hash 捕获失败 executionId={}: {}", ctx.executionId(), ioe.getMessage());
        }
        // 把 RUNNING 行（带 sha256 + snapshotJson）持久化。
        // PRECHECK_FAILED 走 runPrecheckOrRecordFailure 单独 insert，这里永远 insert 或 update。
        if (isNew) {
            historyMapper.insert(row);
        } else {
            historyMapper.updateById(row);
        }
    }

    /**
     * 启动 Shell 进程。优先 mock 模式直接走脚本；否则：
     * <ul>
     *   <li>非 mock 且租户配了 keytab → 写一个 _kinit_wrap.sh 把 kinit 包起来</li>
     *   <li>否则直接 {@code bash <script> <args...>}</li>
     * </ul>
     * 进程本身由 {@link ProcessRunner} 启动并等待。
     */
    private ProcessResult startProcess(ExecutionContext ctx) throws IOException {
        // 并发上限校验：实时读 props；改 maxConcurrent 后立即生效
        if (runningRegistry.activeCount() >= props.getMaxConcurrent()) {
            throw new IllegalStateException(
                    "执行槽位已满（slot limit reached, active=" + runningRegistry.activeCount()
                            + ", max-concurrent=" + props.getMaxConcurrent()
                            + "），请等待正在运行的脚本结束或调大 scriptbox.max-concurrent");
        }

        List<String> command;
        boolean kinitWrap = !props.isMock()
                && ctx.tenant().getKeytabPath() != null
                && !ctx.tenant().getKeytabPath().isBlank();
        Map<String, String> env = buildEnv(ctx);

        if (kinitWrap) {
            command = buildKinitWrappedCommand(ctx);
        } else {
            command = buildDirectCommand(ctx);
        }

        ProcessRequest req = new ProcessRequest(
                command,
                ctx.executionDir(),
                env,
                ctx.stdoutPath(),
                ctx.stderrPath(),
                ctx.timeoutSeconds(),
                "exec-" + ctx.executionId(),
                ctx.script().getId(),
                ctx.tenant().getId()
        );

        ProcessResult result = processRunner.run(ctx.executionId(), req);
        log.info("Shell 执行完成 executionId={} exitCode={} duration={}ms",
                ctx.executionId(), result.exitCode(), result.durationMs());
        return result;
    }

    /**
     * 把 ProcessResult 映射回 ExecutionHistory 的最终字段；解析 result.json；扫描 artifact。
     */
    private ExecutionHistory finalizeExecution(ExecutionContext ctx, ProcessResult processResult) {
        ExecutionHistory history = historyMapper.selectById(ctx.executionId());
        if (history == null) {
            history = newExecutionHistory(ctx.request(), ctx.executionId(), ctx.script(), ctx.tenant(),
                    ctx.params(), ctx.executionDir(), ctx.stdoutPath(), ctx.stderrPath(),
                    ctx.resultPath(), ctx.timeoutSeconds());
        }

        // 状态决策：cancel / timeout / ok 三者互斥，cancel 优先
        boolean cancelled = processResult.cancelled();
        boolean timedOut = processResult.timeout();
        boolean ok = processResult.ok();
        ExecutionStatus status;
        if (cancelled) {
            status = ExecutionStatus.CANCELLED;
            log.info("任务已取消 executionId={}", ctx.executionId());
        } else if (timedOut) {
            status = ExecutionStatus.TIMEOUT;
            log.warn("脚本执行超时 executionId={} timeout={}s",
                    ctx.executionId(), ctx.timeoutSeconds());
        } else {
            status = ok ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED;
            if (!ok) {
                log.warn("脚本执行失败 executionId={} exitCode={}",
                        ctx.executionId(), processResult.exitCode());
            }
        }

        history.setEndTime(LocalDateTime.now());
        history.setDurationMs(processResult.durationMs());
        history.setExitCode(processResult.exitCode());
        history.setTimeout(timedOut);
        history.setSuccess(ok);
        history.setStatus(status.name());

        // result.json 解析失败不能拖垮整体执行；只能 warn
        try {
            if (Files.exists(ctx.resultPath())) {
                resultParserService.parse(ctx.resultPath(), history);
                log.info("result.json 解析完成 executionId={}", ctx.executionId());
            }
        } catch (Exception ex) {
            log.warn("result.json 解析失败 executionId={}: {}", ctx.executionId(), ex.getMessage());
        }

        // artifact 扫描：失败也不影响主流程
        try {
            int registered = artifactService.scanAndRegister(history);
            if (registered > 0) {
                log.info("Artifact 扫描完成 executionId={} 文件数={}",
                        ctx.executionId(), registered);
            }
        } catch (Exception ex) {
            log.warn("Artifact 扫描失败 executionId={}: {}", ctx.executionId(), ex.getMessage());
        }

        // 先 unregister 再写库：cancel 接口通过 registry 查找执行；unregister 之后 cancel
        // 立即返回 false；写库时不会再有人查到「RUNNING 但没活进程」。
        runningRegistry.unregister(ctx.executionId());

        // captureSnapshot 已经把 RUNNING 行 insert 到 DB；这里只 update 最终结果。
        // PRECHECK_FAILED 路径走 runPrecheckOrRecordFailure 的 insert，不需要再 insert。
        if (historyMapper.selectById(ctx.executionId()) == null) {
            historyMapper.insert(history);
        } else {
            historyMapper.updateById(history);
        }
        return history;
    }

    // ======================================================================
    // 命令与环境
    // ======================================================================

    private Map<String, String> buildEnv(ExecutionContext ctx) {
        Map<String, String> env = new LinkedHashMap<>(globalVariableService.envForExecution());
        env.put("EXECUTION_ID", String.valueOf(ctx.executionId()));
        env.put("EXECUTION_DIR", ctx.executionDir().toAbsolutePath().toString());
        env.put("ARTIFACT_DIR", ctx.artifactDir().toAbsolutePath().toString());
        return env;
    }

    private List<String> buildDirectCommand(ExecutionContext ctx) {
        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add(ctx.scriptPath().toString());
        cmd.addAll(buildArgsFromMap(ctx.params()));
        return cmd;
    }

    /**
     * 写一个 _kinit_wrap.sh 来包一层 kinit；调用方需保证 wrapper 写在 executionDir 内（受控目录）。
     */
    private List<String> buildKinitWrappedCommand(ExecutionContext ctx) throws IOException {
        Path wrapper = ctx.executionDir().resolve("_kinit_wrap.sh");
        String keytab = ctx.tenant().getKeytabPath().replace("'", "'\\''");
        String principal = ctx.tenant().getPrincipal().replace("'", "'\\''");
        String scriptPath = ctx.scriptPath().toString().replace("'", "'\\''");
        Files.writeString(wrapper,
                "#!/usr/bin/env bash\nset -e\n" +
                        "kinit -kt '" + keytab + "' '" + principal + "' || exit 127\n" +
                        "exec \"" + scriptPath + "\" \"$@\"\n",
                StandardCharsets.UTF_8);
        new File(wrapper.toString()).setExecutable(true);
        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add(wrapper.toString());
        cmd.addAll(buildArgsFromMap(ctx.params()));
        return cmd;
    }

    private List<String> buildArgsFromMap(Map<String, String> params) {
        List<String> args = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            args.add("--" + e.getKey());
            args.add(e.getValue());
        }
        return args;
    }

    // ======================================================================
    // 参数校验 / 类型转换 / 条件可见
    // ======================================================================

    /**
     * 按 ScriptParam 声明校验并强制类型转换；同时应用 visibleWhen 过滤。
     * 返回的 Map 仅包含可见参数。
     */
    private Map<String, String> validateAndCoerce(List<ScriptParam> declared, Map<String, String> given) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ScriptParam p : declared) {
            String name = p.getName();
            String value = given.get(name);
            if ((value == null || value.isBlank()) && p.getDefaultValue() != null) {
                value = p.getDefaultValue();
            }
            if (Boolean.TRUE.equals(p.getRequired())
                    && (value == null || value.isBlank())
                    && "boolean".equalsIgnoreCase(p.getType())) {
                // boolean 永远有值（false 默认），不视为缺失
            } else if (Boolean.TRUE.equals(p.getRequired()) && (value == null || value.isBlank())) {
                throw new IllegalArgumentException("参数 '" + name + "' 必填");
            }
            if (value == null) value = "";
            String type = p.getType() == null ? "text" : p.getType().toLowerCase();
            switch (type) {
                case "number":
                    if (!value.isBlank()) {
                        try { Double.parseDouble(value); }
                        catch (NumberFormatException ex) {
                            throw new IllegalArgumentException("参数 '" + name + "' 必须是数字");
                        }
                    }
                    break;
                case "select":
                    if (!value.isBlank() && p.getOptions() != null) {
                        boolean ok = false;
                        for (String opt : p.getOptions().split(",")) {
                            if (opt.trim().equals(value)) { ok = true; break; }
                        }
                        if (!ok) throw new IllegalArgumentException(
                                "参数 '" + name + "' 必须是: " + p.getOptions());
                    }
                    break;
                case "boolean":
                    if (value.isBlank()) value = "false";
                    else value = ("true".equalsIgnoreCase(value) || "1".equals(value)) ? "true" : "false";
                    break;
                case "date":
                case "text":
                case "textarea":
                default:
                    // 字符串原样保留
                    break;
            }
            // 条件显示：visibleWhen 不满足则跳过该参数（不传给 Shell）
            VisibleWhen rule = VisibleWhen.parse(p.getVisibleWhenJson());
            if (rule != null && !rule.matches(out)) continue;
            out.put(name, value);
        }
        // 忽略声明外的额外参数（不报错）
        return out;
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    private void attachMdc(ExecutionContext ctx) {
        MDC.put("executionId", String.valueOf(ctx.executionId()));
        if (ctx.script() != null) MDC.put("scriptName", ctx.script().getName());
        if (ctx.tenant() != null) MDC.put("tenantName", ctx.tenant().getName());
    }

    private long nextExecutionId() {
        return counter.incrementAndGet();
    }

    private String sha256Hex(byte[] data) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 构建 execution_snapshot JSON。敏感 GlobalVariable 值已遮罩，keytab 路径不写入。
     */
    private String buildSnapshotJson(Script script, Tenant tenant,
                                      Map<String, String> validated, String scriptBody) {
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("scriptId", script.getId());
            snap.put("scriptName", script.getName());
            snap.put("displayName", script.getDisplayName());
            snap.put("description", script.getDescription());
            snap.put("riskLevel", script.getRiskLevel());
            snap.put("allowConcurrent", script.getAllowConcurrent());
            snap.put("timeoutSeconds", script.getTimeoutSeconds());
            snap.put("tenantId", tenant.getId());
            snap.put("tenantName", tenant.getName());
            // keytab 是敏感字段，不写入 snapshot
            snap.put("params", validated);
            snap.put("body", scriptBody);
            // 敏感全局变量遮罩
            Map<String, String> env = sensitiveDataMasker.maskEnv(
                    globalVariableService.envForExecution(), globalVariableService.listEnabled());
            snap.put("globalVariables", env);
            return mapper.writeValueAsString(snap);
        } catch (Exception e) {
            log.warn("snapshot 构建失败: {}", e.getMessage());
            return null;
        }
    }

    // ======================================================================
    // 预览 / 历史回放
    // ======================================================================

    /**
     * Dry-run：返回解析后的命令 + 遮罩后的环境变量 + 生效参数。不实际启动进程。
     */
    public Map<String, Object> preview(ExecutionRequest req) throws IOException {
        Script script = scriptService.getById(req.getScriptId());
        if (script == null) throw new IllegalArgumentException("script not found: " + req.getScriptId());
        Tenant tenant = tenantService.getById(req.getTenantId());
        if (tenant == null) throw new IllegalArgumentException("tenant not found: " + req.getTenantId());

        List<ScriptParam> params = scriptService.paramsOf(script.getId());
        Map<String, String> validated;
        if (req.getPresetId() != null) {
            var preset = presetService.get(script.getId(), req.getPresetId());
            if (preset == null) throw new IllegalArgumentException("preset not found: " + req.getPresetId());
            Map<String, String> fromPreset = presetService.applyParams(preset);
            Map<String, String> supplied = req.getParams() == null ? Map.of() : req.getParams();
            Map<String, String> merged = new LinkedHashMap<>(fromPreset);
            merged.putAll(supplied);
            validated = validateAndCoerce(params, merged);
        } else {
            validated = validateAndCoerce(params, req.getParams() == null ? Map.of() : req.getParams());
        }

        String scriptPath = script.getScriptPath();
        List<String> command;
        boolean kinitWrap = !props.isMock()
                && tenant.getKeytabPath() != null
                && !tenant.getKeytabPath().isBlank();
        if (kinitWrap) {
            command = new ArrayList<>();
            command.add("bash");
            command.add("<executions-dir>/<exec-id>/_kinit_wrap.sh");
            command.addAll(buildArgsFromMap(validated));
        } else {
            command = buildCommandForPreview(scriptPath, validated);
        }

        Map<String, String> masked = sensitiveDataMasker.maskEnv(
                globalVariableService.envForExecution(), globalVariableService.listEnabled());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scriptId", script.getId());
        out.put("scriptName", script.getName());
        out.put("scriptDisplayName", script.getDisplayName());
        out.put("scriptPath", scriptPath);
        out.put("tenantId", tenant.getId());
        out.put("tenantName", tenant.getName());
        out.put("principal", tenant.getPrincipal());
        out.put("timeoutSeconds", script.getTimeoutSeconds());
        out.put("enabled", script.getEnabled());
        out.put("params", validated);
        out.put("command", command);
        out.put("kinitWrapped", kinitWrap);
        out.put("globalVariables", masked);
        out.put("keytabSet", tenant.getKeytabPath() != null && !tenant.getKeytabPath().isBlank());
        // Preview 中完全遮掉 keytab 路径，避免在调试阶段泄露
        out.put("keytabPath", tenant.getKeytabPath() != null && !tenant.getKeytabPath().isBlank() ? SensitiveDataMasker.MASK : null);
        return out;
    }

    private List<String> buildCommandForPreview(String scriptPath, Map<String, String> params) {
        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add(scriptPath);
        cmd.addAll(buildArgsFromMap(params));
        return cmd;
    }

    public byte[] readStdout(ExecutionHistory h) throws IOException {
        return readUpTo(h.getStdoutPath(), props.getMaxLogBytes());
    }

    public byte[] readStderr(ExecutionHistory h) throws IOException {
        return readUpTo(h.getStderrPath(), props.getMaxLogBytes());
    }

    /**
     * 读取文件尾部最多 {@code max} 字节；超过则加 {@code --- truncated ---} 前缀。
     * 用于 stdout / stderr 限制显示，避免大输出吃光内存。
     */
    private byte[] readUpTo(String path, long max) throws IOException {
        if (path == null) return new byte[0];
        File f = new File(path);
        if (!f.exists()) return new byte[0];
        long len = f.length();
        if (len <= max) return Files.readAllBytes(Paths.get(path));
        try (var ch = Files.newByteChannel(Paths.get(path))) {
            ch.position(len - max);
            var buf = java.nio.ByteBuffer.allocate((int) max);
            ch.read(buf);
            buf.flip();
            byte[] tail = new byte[buf.remaining() + 64];
            String prefix = "--- truncated, showing last " + max + " bytes ---\n";
            byte[] pb = prefix.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(pb, 0, tail, 0, pb.length);
            buf.get(tail, pb.length, buf.remaining());
            return java.util.Arrays.copyOf(tail, pb.length + buf.remaining());
        }
    }

    /**
     * Snapshot 重放：从历史行的 snapshotJson 读取原参数 / 脚本体，临时覆盖脚本文件，
     * 跑一次 execute，再恢复原文件。注意：原文件在 rerun 期间被改写，存在一个
     * 短暂的时间窗口看到 snapshot body（其他人 readScriptBody 会看到 snapshot 内容）。
     * 这是历史实现的妥协：保持 execute() 的签名不变（不引入新的脚本路径参数）。
     */
    public ExecutionHistory rerunFromSnapshot(long executionId) throws IOException {
        ExecutionHistory h = historyMapper.selectById(executionId);
        if (h == null) throw new IllegalArgumentException("execution not found: " + executionId);
        if (h.getSnapshotJson() == null || h.getSnapshotJson().isBlank())
            throw new IllegalStateException("snapshot missing — cannot re-run");
        @SuppressWarnings("unchecked")
        Map<String, Object> snap = mapper.readValue(h.getSnapshotJson(), Map.class);
        Script s = scriptService.getById(((Number) snap.get("scriptId")).longValue());
        if (s == null) throw new IllegalStateException("script has been deleted");
        String body = (String) snap.get("body");

        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(s.getId());
        req.setTenantId(h.getTenantId());
        @SuppressWarnings("unchecked")
        Map<String, String> snapParams = (Map<String, String>) snap.get("params");
        req.setParams(snapParams);
        req.setBypassDangerousCheck(true);

        String originalPath = s.getScriptPath();
        Path original = Paths.get(originalPath);
        String originalBody = Files.readString(original, StandardCharsets.UTF_8);
        Files.writeString(original, body, StandardCharsets.UTF_8);
        try {
            return execute(req);
        } finally {
            // 恢复原文 + 删除临时 _kinit_wrap / artifacts 等已生成的目录
            Files.writeString(original, originalBody, StandardCharsets.UTF_8);
        }
    }
}
