package com.bigdata.scriptbox.executor;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.dto.ExecutionRequest;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次执行的所有运行时上下文。
 *
 * <p>把 {@code ScriptExecutor.execute} 内部原本散落在方法体中的 8+ 个相互依赖字段
 * （executionId / script / tenant / params / execDir / artifactDir / env / process）
 * 抽成一个不可变 record，便于：
 * <ol>
 *   <li>在 {@code buildCommand} / {@code setupEnv} / drain 等辅助方法之间传递；</li>
 *   <li>未来做执行快照 / 重放 / 异步审计时直接序列化；</li>
 *   <li>显著降低原方法签名上的复杂度。</li>
 * </ol>
 *
 * <p>注意：本类不是万能参数对象，只承载执行生命周期所需的数据；ResultParser、
 * ArtifactService 等仍走依赖注入。
 */
public record ExecutionContext(
        long executionId,
        ExecutionRequest request,
        Script script,
        Tenant tenant,
        Map<String, String> params,
        Path executionDir,
        Path artifactDir,
        Path stdoutPath,
        Path stderrPath,
        Path resultPath,
        Path scriptPath,
        boolean kinitWrapped,
        int timeoutSeconds,
        /** 临时 kinit wrapper 文件路径；执行结束（成功/失败/取消）必须删除。null 表示未生成 wrapper。 */
        Path wrapperPath,
        /** snapshot rerun 标记：为 true 时 captureSnapshot 跳过 scriptsRoot 路径校验，
         *  因为 rerun 临时脚本副本落在 executionDir 内（同样受控，但不在 scriptsRoot 下）。 */
        boolean rerunSnapshot
) {
    /** 构造后保持 Map 不可变（防御性拷贝，防止调用方后续修改 params）。 */
    public ExecutionContext {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** builder-style 构造器，方便 ScriptExecutor 内部分步填充字段。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 构造器：链式调用，按需填字段。 */
    public static final class Builder {
        private long executionId;
        private ExecutionRequest request;
        private Script script;
        private Tenant tenant;
        private Map<String, String> params = new LinkedHashMap<>();
        private Path executionDir;
        private Path artifactDir;
        private Path stdoutPath;
        private Path stderrPath;
        private Path resultPath;
        private Path scriptPath;
        private boolean kinitWrapped;
        private int timeoutSeconds = 600;
        private Path wrapperPath;
        private boolean rerunSnapshot;

        public Builder executionId(long v) { this.executionId = v; return this; }
        public Builder request(ExecutionRequest v) { this.request = v; return this; }
        public Builder script(Script v) { this.script = v; return this; }
        public Builder tenant(Tenant v) { this.tenant = v; return this; }
        public Builder params(Map<String, String> v) { this.params = v == null ? Map.of() : v; return this; }
        public Builder executionDir(Path v) { this.executionDir = v; return this; }
        public Builder artifactDir(Path v) { this.artifactDir = v; return this; }
        public Builder stdoutPath(Path v) { this.stdoutPath = v; return this; }
        public Builder stderrPath(Path v) { this.stderrPath = v; return this; }
        public Builder resultPath(Path v) { this.resultPath = v; return this; }
        public Builder scriptPath(Path v) { this.scriptPath = v; return this; }
        public Builder kinitWrapped(boolean v) { this.kinitWrapped = v; return this; }
        public Builder timeoutSeconds(int v) { this.timeoutSeconds = v; return this; }
        public Builder wrapperPath(Path v) { this.wrapperPath = v; return this; }
        public Builder rerunSnapshot(boolean v) { this.rerunSnapshot = v; return this; }

        public ExecutionContext build() {
            return new ExecutionContext(executionId, request, script, tenant, params,
                    executionDir, artifactDir, stdoutPath, stderrPath, resultPath,
                    scriptPath, kinitWrapped, timeoutSeconds, wrapperPath, rerunSnapshot);
        }
    }
}