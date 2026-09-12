package com.bigdata.scriptbox.executor;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.exception.BusinessErrorCode;
import com.bigdata.scriptbox.exception.BusinessException;
import com.bigdata.scriptbox.model.RiskLevel;
import com.bigdata.scriptbox.service.FileUploadService;
import com.bigdata.scriptbox.service.StoragePathService;
import com.bigdata.scriptbox.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 执行上下文准备器（#1 / 拆分 ScriptExecutor 的第一步）。
 *
 * <p>从原 {@code ScriptExecutor.prepareContext} 抽出，纯规划层：
 * <ol>
 *   <li>校验脚本启用状态 / DANGEROUS 二次确认；</li>
 *   <li>加载 + 校验租户；</li>
 *   <li>解析 + 类型校验入参（调用方传入 {@code resolveParams} 函数引用）；</li>
 *   <li>创建执行目录与 artifact 目录；</li>
 *   <li>把 file 参数 promote 到受控路径（仅一次）；</li>
 *   <li>如需 kinit wrapper，临时生成 0700 文件；</li>
 *   <li>组装不可变 {@link ExecutionContext}。</li>
 * </ol>
 *
 * <p>并发准入（{@code ExecutionGate.acquire}）不属于本类 —— 它在 {@code startProcess}
 * 阶段、{@code process.start()} 之前原子完成，避免"准备阶段占用槽位导致提前拒收"的副作用。
 *
 * <p>行为与拆分前完全一致；本类只重组代码，不改语义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionPreparer {

    private final ScriptBoxProperties props;
    private final TenantService tenantService;
    private final StoragePathService storagePathService;
    private final FileUploadService fileUploadService;

    /**
     * 准备执行上下文。
     *
     * @param req               执行请求
     * @param script            已确定的脚本实体
     * @param executionId       本次执行的 ID（调用方在入口处一次性生成）
     * @param scriptBodyOverride snapshot rerun 的脚本正文；null 表示从磁盘读
     * @param resolveParams     调用方提供的参数解析函数（一般是 ScriptExecutor.resolveParams）
     * @return 组装好的不可变 {@link ExecutionContext}
     */
    public ExecutionContext prepare(ExecutionRequest req, Script script, long executionId,
                                    String scriptBodyOverride,
                                    java.util.function.BiFunction<ExecutionRequest, Script, Map<String, String>> resolveParams)
            throws IOException {
        validateScript(script, req);

        Tenant tenant = tenantService.getById(req.getTenantId());
        validateTenant(tenant);

        Map<String, String> validated = resolveParams.apply(req, script);

        Path execDir = storagePathService.executionDirFor(executionId);
        Files.createDirectories(execDir);
        Path artifactDir = storagePathService.artifactsDirFor(executionId);
        // artifact 目录由 ArtifactService 在 scanAndRegister 中创建；
        // 这里调一次 createDirectories 让脚本开始写时目录已存在。
        Files.createDirectories(artifactDir);

        if (req.getFileInputs() != null && !req.getFileInputs().isEmpty()) {
            Map<String, String> resolved =
                    fileUploadService.promoteForExecution(executionId, req.getFileInputs());
            validated.putAll(resolved);
        }

        Path stdoutFile = execDir.resolve("stdout.log");
        Path stderrFile = execDir.resolve("stderr.log");
        Path resultFile = execDir.resolve("result.json");
        int timeoutSeconds = script.getTimeoutSeconds() == null
                ? props.getDefaultTimeoutSeconds() : script.getTimeoutSeconds();

        boolean kinitWrap = !props.isMock()
                && tenant.getKeytabPath() != null
                && !tenant.getKeytabPath().isBlank();
        Path wrapperPath = null;
        if (kinitWrap) {
            // kinit wrapper 生成需要 ExecutionContext（脚本路径），先构造一个临时 ctx
            ExecutionContext tmp = ExecutionContext.builder()
                    .executionId(executionId).tenant(tenant)
                    .executionDir(execDir).scriptPath(resolveScriptPath(script, execDir))
                    .build();
            wrapperPath = createKinitWrapper(tmp, tenant);
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
                .scriptPath(resolveScriptPath(script, execDir))
                .kinitWrapped(kinitWrap)
                .timeoutSeconds(timeoutSeconds)
                .wrapperPath(wrapperPath)
                .rerunSnapshot(req.isRerunSnapshot())
                .scriptBodyOverride(scriptBodyOverride)
                .build();
    }

    /**
     * 解析脚本正文所落的路径。snapshot rerun 时 {@code script.scriptPath} 是空串，
     * 指向 executionDir 内的一个虚拟路径，仅供日志与命令展示使用。
     */
    public Path resolveScriptPath(Script script, Path execDir) {
        String raw = script.getScriptPath();
        if (raw == null || raw.isBlank()) {
            return execDir.resolve("script.sh");
        }
        return Path.of(raw);
    }

    // ---- 内部 ----

    private void validateScript(Script script, ExecutionRequest req) {
        if (script.getEnabled() == null || !script.getEnabled()) {
            throw new BusinessException(BusinessErrorCode.SCRIPT_DISABLED,
                    "脚本已禁用: " + script.getName());
        }
        if (RiskLevel.DANGEROUS.equals(script.getRiskLevel())
                && !req.isBypassDangerousCheck()
                && !RiskLevel.CONFIRM_TOKEN.equals(req.getConfirmToken())) {
            throw new BusinessException(BusinessErrorCode.DANGEROUS_CONFIRM_REQUIRED,
                    "脚本标记为 DANGEROUS，请在前端输入 " + RiskLevel.CONFIRM_TOKEN + " 后重新执行");
        }
    }

    private void validateTenant(Tenant tenant) {
        if (tenant == null) {
            throw new BusinessException(BusinessErrorCode.TENANT_NOT_FOUND, "租户不存在");
        }
        if (tenant.getEnabled() == null || !tenant.getEnabled()) {
            throw new BusinessException(BusinessErrorCode.TENANT_DISABLED, "租户已禁用: " + tenant.getName());
        }
    }

    /**
     * 生成 0700 临时 kinit wrapper 脚本（让 finalizeExecution 的 finally 能可靠清理）。
     * 与拆分前的 createKinitWrapper 完全等价。
     */
    private Path createKinitWrapper(ExecutionContext ctx, Tenant tenant) throws IOException {
        Path wrapper = Files.createTempFile(ctx.executionDir(), "kinit_wrap_", ".sh");
        try {
            Files.setPosixFilePermissions(wrapper, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统：跳过权限设置
        }
        String keytab = shellQuote(tenant.getKeytabPath());
        String principal = shellQuote(tenant.getPrincipal());
        String scriptPath = shellQuote(ctx.scriptPath().toString());
        Files.writeString(wrapper,
                "#!" + props.getShellExecutable() + "\nset -e\n" +
                        shellQuote(props.getKinitExecutable()) + " -kt " + keytab + " " + principal
                        + " || exit 127\n" +
                        "exec \"" + scriptPath + "\" \"$@\"\n",
                java.nio.charset.StandardCharsets.UTF_8);
        return wrapper;
    }

    /** 与 ScriptExecutor 拆分前的实现一致：单引号包裹并转义单引号。 */
    private static String shellQuote(String raw) {
        if (raw == null) return "''";
        return "'" + raw.replace("'", "'\\''") + "'";
    }
}