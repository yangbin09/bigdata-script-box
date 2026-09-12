package com.bigdata.scriptbox.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * BigData Script Box 应用配置项。
 *
 * <p>通过 {@code @ConfigurationProperties(prefix = "scriptbox")} 绑定 application.yml
 * 中所有以 {@code scriptbox.} 开头的配置项。配置范围包括：
 * <ul>
 *   <li>Mock / 真实模式开关（{@link #isMock()}）；</li>
 *   <li>数据 / 脚本 / keytab / 执行 / 日志各类受控目录；</li>
 *   <li>脚本、输入文件、执行产物的体积 / 数量上限；</li>
 *   <li>并发执行上限（{@link #getMaxConcurrent()}）；</li>
 *   <li>批量执行的行数 / 并发上限（{@link #getMaxBatchRows()} 等）；</li>
 *   <li>清理功能保留天数（{@link #getRetentionHistoryDays()} 等）。</li>
 * </ul>
 *
 * <p>负值 / 0 由显式 setter 归一为合法值；正数下界由 Bean Validation 在启动期
 * fail-fast（{@link Validated} + {@link Min}）。这样比每个字段单独写
 * {@code Math.max(1, v)} 更易发现"配置错了"的真实原因。
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "scriptbox")
public class ScriptBoxProperties {
    /** 是否启用 Mock 模式。true 时跳过真实 kinit / Hadoop 调用，方便本地开发。 */
    private boolean mock = true;
    /** 数据根目录（包含 scripts / keytabs / executions 等子目录的根）。 */
    private String dataDir = "./data";
    /** 脚本文件受控目录。 */
    private String scriptsDir = "./data/scripts";
    /** keytab 受控目录。 */
    private String keytabsDir = "./data/keytabs";
    /** 执行结果受控目录（每个 execution 一个子目录）。 */
    private String executionsDir = "./data/executions";

    // V3: 子进程可执行文件路径。部署目标是 Linux 节点，默认 "bash" 走 PATH；
    // 本地开发（尤其是 Windows + WSL）必须能覆盖，因为 C:\Windows\system32\bash.exe
    // 会把 C:\Users\... 吞成 C:Users...，导致任何带路径参数的 bash 调用失败。
    // 例：scriptbox.shell-executable="C:/Program Files/Git/bin/bash.exe"
    private String shellExecutable = "bash";
    /** kinit 可执行文件路径（租户连通性测试用）。 */
    private String kinitExecutable = "kinit";
    /** klist 可执行文件路径（租户连通性测试用）。 */
    private String klistExecutable = "klist";

    /** 单次日志返回的最大字节数（前端分页拉取）。 */
    private long maxLogBytes = 1048576L;
    /** 单个脚本文件最大字节数。 */
    private long maxScriptBytes = 1048576L;
    /** 文件参数上传的最大字节数（10 MB）。 */
    private long maxInputFileBytes = 10485760L; // 10 MB
    /** 脚本未显式声明超时时的默认执行超时（秒）。schema.sql 的 DEFAULT 需与此保持一致。 */
    @Min(1)
    private int defaultTimeoutSeconds = 600;
    // V2: 全局同时执行上限，由 ExecutionGate 的准入许可强制控制。
    // 默认 5 既能覆盖正常使用，又能在失控 fork 时保护宿主机。
    @Min(1)
    private int maxConcurrent = 5;
    // V3: 单次执行 stdout / stderr 落盘字节上限，防止脚本死循环输出打满磁盘。
    // 0 表示不限制（不推荐）。默认 256 MB，与应用日志滚动策略同量级。
    private long maxOutputBytes = 268435456L;
    // V3: 内部短命令（bash -n 语法检查、kinit / klist 连通性测试）的超时（秒）。
    // 这些命令不占用 maxConcurrent 槽位，但必须有自己的硬超时，避免挂死请求线程。
    @Min(1)
    private int commandTimeoutSeconds = 30;
    // V2: 单次执行的执行产物限制。脚本写到 $ARTIFACT_DIR，
    // 结束后扫描注册，超限文件 WARN 跳过而非中断执行。
    private long maxArtifactBytes = 52428800L; // 50 MB 每文件
    @Min(1)
    private int maxArtifactFiles = 50;         // 最多 50 个文件
    // V2: 批量执行的硬上限。避免单次批次把 ExecutionGate 槽位 / 内存打满；
    // 此前是 BatchService 的硬编码常量（MAX_ROWS=200、并发 8）。
    @Min(1)
    private int maxBatchRows = 200;
    @Min(1)
    private int maxBatchConcurrency = 8;
    // V2: 清理保留天数。CleanupService 优先读 system_setting 覆盖值，再回退到这里的默认值。
    // 0 表示禁用对应类别。
    @Min(0)
    private int retentionHistoryDays = 30;
    @Min(0)
    private int retentionArtifactDays = 30;
    @Min(0)
    private int retentionExecutionDays = 30;
    /** 应用日志保留天数（Logback 仅保留最近 3 天的滚动文件）。 */
    @Min(0)
    private int retentionLogDays = 3;
    // V2: Logback 日志目录。Cleanup 只会清理存在的目录；
    // 目录不存在时日志保留类别自动禁用。
    private String logsDir = "./logs";

    // V3 (PR-0): 异步执行配置。ExecutionRunner 读这一段。
    // 关闭时 ExecutionRunner.submit() 直接退化为 ScriptExecutor.execute() 同步路径，
    // 老调用方（同步 await）仍可用，便于回滚。
    private Exec exec = new Exec();

    /** 异步执行配置。单独成块避免污染主配置类的字段表。 */
    @Data
    public static class Exec {
        /** 是否启用异步执行。false 时回退同步路径。 */
        private boolean asyncEnabled = true;
        /** 异步执行池的 max size；与 ExecutionGate.maxConcurrent 解耦，但建议对齐。 */
        @Min(1)
        private int runnerPoolSize = 5;
        /** 队列容量；超过后 CallerRunsPolicy 兜底，调用方线程同步跑。 */
        @Min(1)
        private int runnerQueueSize = 64;
        /** 线程名前缀。 */
        private String runnerNamePrefix = "exec-runner-";
    }

    // ---- 显式 setter 保留"负值归一"逻辑（@Min 在字段上做 fail-fast；负值走到 setter 后
    // 也被钳住，防止 -1 让 setDefaultTimeoutSeconds 误用 600 后还能误归一）。 ----

    public void setMaxConcurrent(int v) { this.maxConcurrent = Math.max(1, v); }
    public void setMaxArtifactFiles(int v) { this.maxArtifactFiles = Math.max(1, v); }
    public void setMaxBatchRows(int v) { this.maxBatchRows = Math.max(1, v); }
    public void setMaxBatchConcurrency(int v) { this.maxBatchConcurrency = Math.max(1, v); }
    public void setRetentionHistoryDays(int v) { this.retentionHistoryDays = Math.max(0, v); }
    public void setRetentionArtifactDays(int v) { this.retentionArtifactDays = Math.max(0, v); }
    public void setRetentionExecutionDays(int v) { this.retentionExecutionDays = Math.max(0, v); }
    public void setRetentionLogDays(int v) { this.retentionLogDays = Math.max(0, v); }
    public void setDefaultTimeoutSeconds(int v) { this.defaultTimeoutSeconds = v <= 0 ? 600 : v; }
    public void setCommandTimeoutSeconds(int v) { this.commandTimeoutSeconds = v <= 0 ? 30 : v; }

    /** 输出上限允许 0（表示不限）。负值由 setter 归一为 0；这里不挂 @Min 让"不限制"合法。 */
    public void setMaxOutputBytes(long v) { this.maxOutputBytes = Math.max(0, v); }
}