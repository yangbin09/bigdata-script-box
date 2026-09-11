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
 * <p>为什么需要这个 record：
 * <ol>
 *   <li>原 {@code ScriptExecutor.execute(ExecutionRequest)} 在内部拼装 8 个相互依赖的字段
 *       （executionId / script / tenant / params / execDir / artifactDir / env / process），
 *       把 280 行方法签名上的临时变量都暴露在方法体中，可读性差。</li>
 *   <li>在多处辅助方法（{@code buildCommand}、{@code setupEnv}、{@code drain}）之间传递时
 *       容易出现「忘记一个字段就编译失败」的问题。</li>
 *   <li>未来要做的快照 / 重放 / 异步审计都需要一个不可变快照，record 是天然载体。</li>
 * </ol>
 *
 * <p>注意：本类不是万能「参数对象」，它只承载执行生命周期所需的数据；超出范围
 * 的字段（如 ResultParser、ArtifactService）继续走依赖注入。
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
        int timeoutSeconds
) {
    /** 构造后保持 Map 不可变（防御性拷贝）。 */
    public ExecutionContext {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** builder-style 构造器，方便 ScriptExecutor 内部分步填充字段。 */
    public static Builder builder() {
        return new Builder();
    }

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

        public ExecutionContext build() {
            return new ExecutionContext(executionId, request, script, tenant, params,
                    executionDir, artifactDir, stdoutPath, stderrPath, resultPath,
                    scriptPath, kinitWrapped, timeoutSeconds);
        }
    }
}
