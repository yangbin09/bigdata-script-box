package com.bigdata.scriptbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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
 *   <li>清理功能保留天数（{@link #getRetentionHistoryDays()} 等）。</li>
 * </ul>
 *
 * <p>部分 setter 含边界校验（如并发数 ≥ 1、保留天数 ≥ 0），由 Lombok 生成默认
 * setter 后再通过本类内的显式 setter 覆盖即可保留校验逻辑。
 */
@Data
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
    /** 单次日志返回的最大字节数（前端分页拉取）。 */
    private long maxLogBytes = 1048576L;
    /** 单个脚本文件最大字节数。 */
    private long maxScriptBytes = 1048576L;
    /** 文件参数上传的最大字节数（10 MB）。 */
    private long maxInputFileBytes = 10485760L; // 10 MB
    // V2: 全局同时执行上限，由 ProcessRunner 的 Semaphore 强制控制。
    // 默认 5 既能覆盖正常使用，又能在失控 fork 时保护宿主机。
    private int maxConcurrent = 5;
    // V2: 单次执行的执行产物限制。脚本写到 $ARTIFACT_DIR，
    // 结束后扫描注册，超限文件 WARN 跳过而非中断执行。
    private long maxArtifactBytes = 52428800L; // 50 MB 每文件
    private int maxArtifactFiles = 50;         // 最多 50 个文件
    private long maxArtifactTotalBytes = 209715200L; // 200 MB 单次执行总产物
    // V2: 清理保留天数。CleanupService 优先读 system_setting 覆盖值，再回退到这里的默认值。
    // 0 表示禁用对应类别。
    private int retentionHistoryDays = 30;
    private int retentionArtifactDays = 30;
    private int retentionExecutionDays = 30;
    /** 应用日志保留天数（Logback 仅保留最近 3 天的滚动文件）。 */
    private int retentionLogDays = 3;
    // V2: Logback 日志目录。Cleanup 只会清理存在的目录；
    // 目录不存在时日志保留类别自动禁用。
    private String logsDir = "./logs";

    /** 显式 setter：保留原有的"并发上限最小为 1"边界校验。 */
    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
    }

    /** 显式 setter：保留原有的"产物文件数最小为 1"边界校验。 */
    public void setMaxArtifactFiles(int maxArtifactFiles) {
        this.maxArtifactFiles = Math.max(1, maxArtifactFiles);
    }

    /** 显式 setter：保留原有的"历史保留天数 ≥ 0"边界校验（0 表示禁用）。 */
    public void setRetentionHistoryDays(int v) {
        this.retentionHistoryDays = Math.max(0, v);
    }

    /** 显式 setter：保留原有的"产物保留天数 ≥ 0"边界校验。 */
    public void setRetentionArtifactDays(int v) {
        this.retentionArtifactDays = Math.max(0, v);
    }

    /** 显式 setter：保留原有的"执行目录保留天数 ≥ 0"边界校验。 */
    public void setRetentionExecutionDays(int v) {
        this.retentionExecutionDays = Math.max(0, v);
    }

    /** 显式 setter：保留原有的"应用日志保留天数 ≥ 0"边界校验。 */
    public void setRetentionLogDays(int v) {
        this.retentionLogDays = Math.max(0, v);
    }
}