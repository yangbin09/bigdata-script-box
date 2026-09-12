package com.bigdata.scriptbox.executor;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ExecutionPreview;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.exception.BusinessErrorCode;
import com.bigdata.scriptbox.exception.BusinessException;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.model.ExecutionStatus;
import com.bigdata.scriptbox.model.VisibleWhen;
import com.bigdata.scriptbox.service.ArtifactService;
import com.bigdata.scriptbox.service.FileUploadService;
import com.bigdata.scriptbox.service.GlobalVariableService;
import com.bigdata.scriptbox.service.PrecheckService;
import com.bigdata.scriptbox.service.PresetService;
import com.bigdata.scriptbox.service.ResultParserService;
import com.bigdata.scriptbox.service.ExecutionGate;
import com.bigdata.scriptbox.service.ExecutionGate.Permit;
import com.bigdata.scriptbox.service.SensitiveDataMasker;
import com.bigdata.scriptbox.service.StoragePathService;
import com.bigdata.scriptbox.service.TenantService;
import com.bigdata.scriptbox.util.MdcContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
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
 *   <li>并发上限与同脚本去重由 {@link ExecutionGate} 的准入许可原子控制；
 *       许可从 {@code pb.start()} 之前一直持有到进程结束。</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ScriptExecutor {

    private final ScriptBoxProperties props;
    private final com.bigdata.scriptbox.service.ScriptService scriptService;
    private final ExecutionHistoryMapper historyMapper;
    private final TenantService tenantService;
    private final GlobalVariableService globalVariableService;
    private final PresetService presetService;
    private final ResultParserService resultParserService;
    private final FileUploadService fileUploadService;
    private final PrecheckService precheckService;
    private final ExecutionGate executionGate;
    private final ArtifactService artifactService;
    private final StoragePathService storagePathService;
    private final SensitiveDataMasker sensitiveDataMasker;
    private final ProcessRunner processRunner;
    private final ObjectMapper mapper;
    private final AtomicLong counter = new AtomicLong(System.currentTimeMillis() * 1000L);

    // V3（#1）：从 ScriptExecutor 拆出的两个组件 —— 准备器（纯规划）和命令构造器。
    // ScriptExecutor 自身只剩"orchestrator"职责，方法体由这些协作类填充。
    private final ExecutionPreparer executionPreparer;
    private final CommandBuilder commandBuilder;

    public ExecutionHistory history(Long id) {
        return historyMapper.selectById(id);
    }

    // ======================================================================
    // 主入口
    // ======================================================================

    public ExecutionHistory execute(ExecutionRequest req) throws IOException {
        Script script = scriptService.getById(req.getScriptId());
        if (script == null)
            throw new IllegalArgumentException("脚本不存在: " + req.getScriptId());
        return executeWithScript(req, script, null);
    }

    /**
     * 使用给定的 Script 实体执行（允许 rerunFromSnapshot 临时覆盖脚本正文）。
     *
     * <p><b>executionId 生命周期</b>：ID 在本方法入口一次性生成，随后贯穿
     * workspace 创建、history 行、进程标签。历史上 ID 在 prepareContext 内被生成
     * 两次（一次用于"临时"文件 promote、一次用于真实执行），导致每次带文件参数的
     * 执行都会在磁盘上留下一个永不清理的孤儿目录与一份重复文件副本。
     *
     * @param req    执行请求
     * @param script 已确定的脚本实体
     * @param scriptBodyOverride snapshot rerun 的脚本正文；null 表示从磁盘读
     */
    private ExecutionHistory executeWithScript(ExecutionRequest req, Script script,
                                               String scriptBodyOverride) throws IOException {
        // V3 (PR-0): 异步路径下 ExecutionRunner 已分配 executionId 并写入 req；
        // 复用它能让前端在"提交瞬间拿到的 ID"和"DB 行写入的 ID"是同一个，
        // 避免 PENDING → RUNNING 切换期间出现 ID 不一致的窗口。
        // 同步路径（async-enabled=false）req.executionId 仍为 null，走自增。
        long executionId = req.getExecutionId() != null ? req.getExecutionId() : nextExecutionId();
        ExecutionContext ctx = prepareContext(req, script, executionId, scriptBodyOverride);
        // MDC 在主流程开始前挂上：runPrecheckOrRecordFailure / captureSnapshot / startProcess
        // 里的 log.info("开始执行脚本 ...") 都需要 executionId / scriptName 上下文；
        // 用 MdcContext.try-with-resources 保证 finally cleanup，避免漏 remove
        // 让池化线程把旧 executionId 串到下一次执行。
        try (MdcContext ignored = buildMdcContext(ctx, req)) {
            // PreCheck 阶段独立处理：失败时也要把 PRECHECK_FAILED 写进 history
            ExecutionHistory precheckFailure = runPrecheckOrRecordFailure(ctx);
            if (precheckFailure != null) {
                return precheckFailure;
            }

            captureSnapshot(ctx);

            log.info("开始执行脚本 executionId={} script={} tenant={} cmd={}",
                    ctx.executionId(), ctx.script().getName(), ctx.tenant().getName(),
                    sensitiveDataMasker.maskCommandList(commandBuilder.buildArgsFromMap(ctx.params())));

            ProcessResult processResult = startProcess(ctx);
            return finalizeExecution(ctx, processResult);
        }
    }

    /**
     * 主流程前 60%：参数 / 路径 / 历史行初始化。
     *
     * <p>本方法<b>不做任何"用临时值试探"的磁盘副作用</b>：executionId 由调用方
     * 生成并传入，脚本正文由调用方提供，文件参数只 promote 一次。
     *
     * @param req    执行请求
     * @param script 已确定的脚本实体（由 execute() 从 DB 加载，或由 rerunFromSnapshot 传入）
     * @param executionId 本次执行的 ID（调用方生成，全局唯一）
     * @param scriptBodyOverride snapshot rerun 的脚本正文；null 表示从磁盘读
     */
    private ExecutionContext prepareContext(ExecutionRequest req, Script script,
                                            long executionId, String scriptBodyOverride) throws IOException {
        // V3（#1）：完整规划步骤下移到 ExecutionPreparer，本方法退化为 1 行委托。
        // resolveParams / validateAndCoerce 仍留在 ScriptExecutor（参数校验是业务规则，
        // 与"执行编排"不同关注点），通过方法引用传给 Preparer。
        return executionPreparer.prepare(req, script, executionId, scriptBodyOverride, this::resolveParams);
    }

    /**
     * 参数合并与校验：preset &lt; supplied，再按 ScriptParam 声明强转 + 可见性过滤。
     */
    private Map<String, String> resolveParams(ExecutionRequest req, Script script) {
        List<ScriptParam> params = scriptService.paramsOf(script.getId());
        Map<String, String> supplied = req.getParams() == null ? Map.of() : req.getParams();
        if (req.getPresetId() == null) {
            return validateAndCoerce(params, supplied);
        }
        var preset = presetService.get(script.getId(), req.getPresetId());
        if (preset == null) throw new IllegalArgumentException("预设不存在: " + req.getPresetId());
        Map<String, String> merged = new LinkedHashMap<>(presetService.applyParams(preset));
        merged.putAll(supplied);
        return validateAndCoerce(params, merged);
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
        PrecheckService.PrecheckReport precheck = precheckService.run(ctx.script(), ctx.tenant());
        if (precheck.ok()) {
            log.info("PreCheck 通过 executionId={}", ctx.executionId());
            return null;
        }

        log.warn("PreCheck 失败 executionId={} script={} message={}",
                ctx.executionId(), ctx.script().getName(), precheck.message());

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
        Path scriptPath = materializeScript(ctx);
        if (scriptPath == null || !Files.exists(scriptPath)) {
            throw new IllegalStateException("脚本文件不存在: " + scriptPath);
        }
        // 路径安全：脚本文件必须落在配置的 scripts 目录下，阻止恶意路径逃逸。
        // 豁免情况：snapshot rerun 的正文副本落在 executionDir 内（同样受控），
        // 不在 scriptsRoot 下；用 ctx.rerunSnapshot() 标记避免穿透 assertInside。
        if (!ctx.rerunSnapshot()) {
            storagePathService.assertInside(scriptPath, storagePathService.scriptsRoot(), "script");
        }

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
        // PRECHECK_FAILED 走 runPrecheckOrRecordFailure 单独 insert，这里永远 upsert。
        upsertHistory(row, isNew);
    }

    /**
     * 脚本正文落盘并返回其路径。
     *
     * <ul>
     *   <li>普通执行（{@code scriptBodyOverride == null}）：直接返回
     *       {@code scriptsRoot/<id>/script.sh}，不复制、不落盘。</li>
     *   <li>snapshot rerun（正文来自快照）：写入
     *       {@code executionDir/script.sh}（随本次执行一起被 Cleanup 回收），
     *       从而不再需要"预生成 executionId + 临时副本 + finally 删除"那套流程。</li>
     * </ul>
     *
     * <p>幂等：同一 executionId 重复调用不会再写一次。
     */
    private Path materializeScript(ExecutionContext ctx) throws IOException {
        String body = ctx.scriptBodyOverride();
        if (body == null) {
            return ctx.scriptPath();
        }
        Path target = ctx.executionDir().resolve("script.sh");
        Files.createDirectories(ctx.executionDir());
        if (!Files.exists(target)) {
            Files.writeString(target, body, StandardCharsets.UTF_8);
        }
        return target;
    }

    /**
     * 统一的 history 行写入：新行 insert，已有行 update。
     *
     * <p>历史上 {@link #captureSnapshot}、{@link #finalizeExecution}、
     * {@link #runPrecheckOrRecordFailure} 各写了一遍 insert/update 判断，
     * 三处规则必须手动同步。
     */
    private void upsertHistory(ExecutionHistory history, boolean isNew) {
        if (isNew || historyMapper.selectById(history.getId()) == null) {
            historyMapper.insert(history);
        } else {
            historyMapper.updateById(history);
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
    /**
     * 启动 Shell 进程。
     *
     * <p>并发准入（同脚本去重 + 全局槽位上限）在 {@code pb.start()} 之前由
     * {@link ExecutionGate#acquire} 在单个临界区内原子完成；许可由 try-with-resources
     * 持有到进程结束，因此槽位不会在"进程已退出但结果还没写库"期间被提前放行。
     *
     * <p>命令形态：租户配了 keytab（非 mock）时先写一个 kinit wrapper 把
     * {@code kinit} 与业务脚本串在同一进程内；否则直接
     * {@code <shell> <script> <args...>}。进程本身由 {@link ProcessRunner} 启动并等待。
     */
    private ProcessResult startProcess(ExecutionContext ctx) throws IOException {
        // 脚本正文落盘（普通执行返回 scriptsRoot 下的原文件；snapshot rerun 写到本次
        // executionDir），随后所有命令构造都使用这个确定路径。
        // 注：实际执行的命令使用 ctx.scriptPath()（由 ExecutionPreparer 决定的路径），
        // 不会受 materializeScript 的落盘副作用影响 —— snapshot rerun 时两者一致。
        materializeScript(ctx);

        // V3（#1）：环境与命令构造全部委托给 CommandBuilder。
        // ScriptExecutor 不再持有 bash 拼装细节，只负责"准备 + 启动 + 收尾"编排。
        List<String> command = commandBuilder.build(ctx);
        Map<String, String> env = commandBuilder.buildEnv(ctx);

        ProcessRequest req = new ProcessRequest(
                command,
                ctx.executionDir(),
                env,
                ctx.stdoutPath(),
                ctx.stderrPath(),
                ctx.timeoutSeconds(),
                "exec-" + ctx.executionId()
        );

        try (Permit permit = executionGate.acquire(
                ctx.executionId(),
                ctx.script().getId(),
                ctx.tenant().getId(),
                ctx.script().getName(),
                Boolean.TRUE.equals(ctx.script().getAllowConcurrent())).orThrow()) {

            ProcessResult result = processRunner.run(ctx.executionId(), req);
            log.info("Shell 执行完成 executionId={} exitCode={} duration={}ms",
                    ctx.executionId(), result.exitCode(), result.durationMs());
            return result;
        }
    }

    /**
     * 把 ProcessResult 映射回 ExecutionHistory 的最终字段；解析 result.json；扫描 artifact。
     *
     * <p>无论最终状态如何（成功 / 失败 / 超时 / 取消 / 异常），都会在 finally 内
     * 删除临时 kinit wrapper 文件，防止敏感 wrapper 在 executionDir 中残留。
     */
    private ExecutionHistory finalizeExecution(ExecutionContext ctx, ProcessResult processResult) {
        try {
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
                } else {
                    log.info("脚本执行成功 executionId={} 耗时={}ms",
                            ctx.executionId(), processResult.durationMs());
                }
            }

            // drain 失败提示：stdout 或 stderr 写入异常时不阻塞主流程，但要让运维看到
            if (processResult.drainFailed()) {
                log.warn("执行输出 drain 不完整 executionId={}（stdout 或 stderr 写入异常）",
                        ctx.executionId());
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

            // 进程已在 ProcessRunner 内结束并从许可上解绑；槽位由 startProcess 的
            // try-with-resources 释放（在进程结束之后、本方法返回之前），
            // 因此不会有人看到「RUNNING 但没活进程」的中间态。

            // captureSnapshot 已经把 RUNNING 行写进 DB；这里只做最终结果 upsert。
            // PRECHECK_FAILED 路径走 runPrecheckOrRecordFailure 的 insert，不需要再 insert。
            upsertHistory(history, false);
            return history;
        } finally {
            // 删除临时 kinit wrapper：执行结束 / 异常 / 取消都必须删，
            // 防止 0700 文件在 executionDir 残留到 Cleanup 阶段（暴露 keytab 路径）。
            if (ctx.wrapperPath() != null) {
                try {
                    Files.deleteIfExists(ctx.wrapperPath());
                } catch (IOException ioe) {
                    log.warn("删除临时 kinit wrapper 失败 executionId={} path={}: {}",
                            ctx.executionId(), ctx.wrapperPath(), ioe.getMessage());
                }
            }
            // 释放本次执行消费掉的 pending 上传副本（<dataRoot>/uploads/<token>/）。
            // 注意：**不**在这里删 <execId>/input/ —— 它和执行目录同在 executionsRoot 下，
            // 由 CleanupService 按 retention-execution-days 统一回收；执行后立刻删除会让
            // 运维无法复核"这次执行到底喂了哪些输入文件"。
            cleanupPendingUploads(ctx.request());
        }
    }

    /**
     * 清理本次执行消费掉的 pending 上传副本（{@code <dataRoot>/uploads/<token>/}）。
     *
     * <p>为什么这个可以删而 {@code <execId>/input/} 不能删：{@code uploads/} 在
     * {@code dataRoot} 下，{@link com.bigdata.scriptbox.service.CleanupService} 只扫
     * {@code executionsRoot} 与 {@code logsRoot}，永远不会碰它 —— 不在这里回收就是
     * 永久泄漏。而 {@code input/} 落在执行目录内，属于 Cleanup 的管辖范围。
     *
     * <p>所有异常静默：清理失败不影响执行结论。
     */
    private void cleanupPendingUploads(ExecutionRequest req) {
        if (req == null || req.getFileInputs() == null || req.getFileInputs().isEmpty()) return;
        for (String src : req.getFileInputs().values()) {
            if (src == null || src.isBlank()) continue;
            try {
                fileUploadService.cleanupPending(src);
            } catch (Exception ex) {
                log.debug("清理 pending 上传失败 executionId={} src={}: {}",
                        req.getBatchId(), src, ex.getMessage());
            }
        }
    }

    // ======================================================================
    // 命令与环境（V3 #1：已下移到 ExecutionPreparer / CommandBuilder）
    // ======================================================================

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

    private MdcContext buildMdcContext(ExecutionContext ctx, ExecutionRequest req) {
        // MDC 集中挂载：buildMdcContext 由 try-with-resources 调用，离开作用域时
        // 自动 MDC.remove，避免历史代码里"漏 remove 一个 key 导致串号"的隐患。
        // 任何后续如果加新 MDC key，只需在这里加一行。
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        map.put("executionId", String.valueOf(ctx.executionId()));
        if (ctx.script() != null) map.put("scriptName", ctx.script().getName());
        if (ctx.tenant() != null) map.put("tenantName", ctx.tenant().getName());
        if (req.getBatchId() != null) map.put("batchId", req.getBatchId());
        if (req.getScenarioId() != null) map.put("scenarioId", String.valueOf(req.getScenarioId()));
        // 把 LinkedHashMap 拆成 flat varargs
        String[] pairs = new String[map.size() * 2];
        int idx = 0;
        for (var e : map.entrySet()) {
            pairs[idx++] = e.getKey();
            pairs[idx++] = e.getValue();
        }
        return MdcContext.of(pairs);
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
     *
     * <p>参数解析走与真实执行**完全相同**的 {@link #resolveParams}，因此 dry-run
     * 不会与真实执行产生语义漂移（历史上这里是 {@code prepareContext} 逻辑的整段复制，
     * 已经在 preset 合并与校验上出现分歧）。
     *
     * <p>V3（#8）：返回类型从 {@code Map<String, Object>}（15 键裸 Map）改为
     * 类型化的 {@link ExecutionPreview} record。字段名与原 Map 键一一对应，
     * Jackson 序列化后 JSON 形态完全不变（前端零改动）。
     */
    public ExecutionPreview preview(ExecutionRequest req) throws IOException {
        Script script = scriptService.getById(req.getScriptId());
        if (script == null) throw new BusinessException(BusinessErrorCode.SCRIPT_NOT_FOUND,
                "script not found: " + req.getScriptId());
        Tenant tenant = tenantService.getById(req.getTenantId());
        if (tenant == null) throw new BusinessException(BusinessErrorCode.TENANT_NOT_FOUND,
                "tenant not found: " + req.getTenantId());

        Map<String, String> validated = resolveParams(req, script);

        // V3（#1+#8）：命令构造与脚本路径解析都委托给 CommandBuilder / ExecutionPreparer，
        // 本方法只负责"加载实体 + 解析参数 + 组装返回值"，与 startProcess 共享同一份命令语义。
        String scriptPath = script.getScriptPath();
        Path execDir = storagePathService.executionDirFor(0L); // 仅用于虚拟路径，ID 在执行时分配
        Path resolvedScriptPath = executionPreparer.resolveScriptPath(script, execDir);

        // preview 不走 ExecutionPreparer.prepare（那里会生成 kinit wrapper + 创建目录），
        // 单独构造一个最小的 ExecutionContext 供 CommandBuilder.buildForPreview 使用。
        boolean kinitWrap = !props.isMock()
                && tenant.getKeytabPath() != null
                && !tenant.getKeytabPath().isBlank();
        ExecutionContext stubCtx = ExecutionContext.builder()
                .executionId(0L)
                .request(req)
                .script(script)
                .tenant(tenant)
                .params(validated)
                .executionDir(execDir)
                .artifactDir(execDir)
                .scriptPath(resolvedScriptPath)
                .kinitWrapped(kinitWrap)
                .timeoutSeconds(script.getTimeoutSeconds() == null
                        ? props.getDefaultTimeoutSeconds() : script.getTimeoutSeconds())
                .rerunSnapshot(false)
                .build();
        List<String> command = commandBuilder.buildForPreview(stubCtx);

        Map<String, String> masked = sensitiveDataMasker.maskEnv(
                globalVariableService.envForExecution(), globalVariableService.listEnabled());

        boolean keytabConfigured = tenant.getKeytabPath() != null && !tenant.getKeytabPath().isBlank();
        return new ExecutionPreview(
                script.getId(),
                script.getName(),
                script.getDisplayName(),
                scriptPath,
                tenant.getId(),
                tenant.getName(),
                tenant.getPrincipal(),
                script.getTimeoutSeconds(),
                script.getEnabled(),
                Map.copyOf(validated),
                List.copyOf(command),
                kinitWrap,
                masked,
                keytabConfigured,
                keytabConfigured ? SensitiveDataMasker.MASK : null);
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
     *
     * <p>{@link java.nio.channels.ByteChannel#read} 允许短读，因此这里循环读到
     * 目标字节数或 EOF。旧实现只读一次，短读时会把未初始化的零字节当作日志内容返回。
     *
     * <p>另外：读取走的字节流可能不是合法 UTF-8（脚本按宿主机 locale 输出、或恰好从
     * 多字节字符中间截断），此时**不能抛异常**把整个日志页打成错误 —— 那会让"脚本能跑但
     * 日志看不了"。异常的字节由调用方按 UTF-8 宽松解码处理。
     */
    private byte[] readUpTo(String path, long max) throws IOException {
        if (path == null) return new byte[0];
        File f = new File(path);
        if (!f.exists()) return new byte[0];
        long len = f.length();
        if (len <= max) return Files.readAllBytes(Paths.get(path));

        int want = (int) Math.min(max, Integer.MAX_VALUE);
        byte[] tail = new byte[want];
        int read = 0;
        try (var ch = Files.newByteChannel(Paths.get(path))) {
            ch.position(len - want);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(tail);
            while (buf.hasRemaining()) {
                int r = ch.read(buf);
                if (r < 0) break;
                read += r;
            }
        }
        String prefix = "--- truncated, showing last " + max + " bytes ---\n";
        byte[] pb = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[pb.length + read];
        System.arraycopy(pb, 0, out, 0, pb.length);
        System.arraycopy(tail, 0, out, pb.length, read);
        return out;
    }

    /**
     * Snapshot 重放：从历史行的 snapshotJson 读取原参数 / 脚本体，把脚本正文作为
     * {@link ExecutionContext#scriptBodyOverride()} 交给本次执行使用。
     *
     * <p>与原实现的关键差别：不再预生成 executionId、不再在 executionDir 内写
     * {@code _rerun_snapshot.sh} 临时副本。正文在 {@link ScriptMaterializer} 落盘到
     * <b>本次执行自己的</b> executionDir 下，随 Cleanup 一起回收；也不再有
     * "先造 ID 再销毁"的 ID 消耗与残留文件。
     */
    public ExecutionHistory rerunFromSnapshot(long executionId) throws IOException {
        ExecutionHistory h = historyMapper.selectById(executionId);
        if (h == null) throw new IllegalArgumentException("execution not found: " + executionId);
        if (h.getSnapshotJson() == null || h.getSnapshotJson().isBlank())
            throw new IllegalStateException("snapshot missing — cannot re-run");
        @SuppressWarnings("unchecked")
        Map<String, Object> snap = mapper.readValue(h.getSnapshotJson(), Map.class);
        if (!(snap.get("scriptId") instanceof Number scriptIdNum)) {
            throw new IllegalStateException("snapshot is missing scriptId — cannot re-run");
        }
        Script s = scriptService.getById(scriptIdNum.longValue());
        if (s == null) throw new IllegalStateException("script has been deleted");
        Object bodyRaw = snap.get("body");
        if (bodyRaw == null) throw new IllegalStateException("snapshot is missing script body");
        String body = String.valueOf(bodyRaw);

        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(s.getId());
        req.setTenantId(h.getTenantId());
        @SuppressWarnings("unchecked")
        Map<String, String> snapParams = (Map<String, String>) snap.get("params");
        req.setParams(snapParams == null ? new LinkedHashMap<>() : snapParams);
        req.setBypassDangerousCheck(true);
        req.setRerunSnapshot(true);

        log.info("snapshot rerun: 按快照正文重放 sourceExecutionId={} scriptId={}", executionId, s.getId());
        return executeWithScript(req, s, body);
    }
}
