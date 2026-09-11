package com.bigdata.scriptbox.config;

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
 */
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

    /**
     * 获取 Mock 模式开关。
     *
     * @return 是否启用 Mock 模式
     */
    public boolean isMock() { return mock; }

    /**
     * 设置 Mock 模式开关。
     *
     * @param mock true 启用 Mock 模式
     */
    public void setMock(boolean mock) { this.mock = mock; }

    /**
     * 获取数据根目录。
     *
     * @return 数据根目录路径
     */
    public String getDataDir() { return dataDir; }

    /**
     * 设置数据根目录。
     *
     * @param dataDir 数据根目录路径
     */
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    /**
     * 获取脚本目录。
     *
     * @return 脚本目录路径
     */
    public String getScriptsDir() { return scriptsDir; }

    /**
     * 设置脚本目录。
     *
     * @param scriptsDir 脚本目录路径
     */
    public void setScriptsDir(String scriptsDir) { this.scriptsDir = scriptsDir; }

    /**
     * 获取 keytab 目录。
     *
     * @return keytab 目录路径
     */
    public String getKeytabsDir() { return keytabsDir; }

    /**
     * 设置 keytab 目录。
     *
     * @param keytabsDir keytab 目录路径
     */
    public void setKeytabsDir(String keytabsDir) { this.keytabsDir = keytabsDir; }

    /**
     * 获取执行目录。
     *
     * @return 执行目录路径
     */
    public String getExecutionsDir() { return executionsDir; }

    /**
     * 设置执行目录。
     *
     * @param executionsDir 执行目录路径
     */
    public void setExecutionsDir(String executionsDir) { this.executionsDir = executionsDir; }

    /**
     * 获取日志最大字节数。
     *
     * @return 日志最大字节数
     */
    public long getMaxLogBytes() { return maxLogBytes; }

    /**
     * 设置日志最大字节数。
     *
     * @param maxLogBytes 日志最大字节数
     */
    public void setMaxLogBytes(long maxLogBytes) { this.maxLogBytes = maxLogBytes; }

    /**
     * 获取脚本最大字节数。
     *
     * @return 脚本最大字节数
     */
    public long getMaxScriptBytes() { return maxScriptBytes; }

    /**
     * 设置脚本最大字节数。
     *
     * @param maxScriptBytes 脚本最大字节数
     */
    public void setMaxScriptBytes(long maxScriptBytes) { this.maxScriptBytes = maxScriptBytes; }

    /**
     * 获取输入文件最大字节数。
     *
     * @return 输入文件最大字节数
     */
    public long getMaxInputFileBytes() { return maxInputFileBytes; }

    /**
     * 设置输入文件最大字节数。
     *
     * @param maxInputFileBytes 输入文件最大字节数
     */
    public void setMaxInputFileBytes(long maxInputFileBytes) { this.maxInputFileBytes = maxInputFileBytes; }

    /**
     * 获取并发上限。
     *
     * @return 并发上限
     */
    public int getMaxConcurrent() { return maxConcurrent; }

    /**
     * 设置并发上限，最小值 1。
     *
     * @param maxConcurrent 并发上限
     */
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = Math.max(1, maxConcurrent); }

    /**
     * 获取单产物最大字节数。
     *
     * @return 单产物最大字节数
     */
    public long getMaxArtifactBytes() { return maxArtifactBytes; }

    /**
     * 设置单产物最大字节数。
     *
     * @param maxArtifactBytes 单产物最大字节数
     */
    public void setMaxArtifactBytes(long maxArtifactBytes) { this.maxArtifactBytes = maxArtifactBytes; }

    /**
     * 获取产物最大文件数。
     *
     * @return 产物最大文件数
     */
    public int getMaxArtifactFiles() { return maxArtifactFiles; }

    /**
     * 设置产物最大文件数，最小值 1。
     *
     * @param maxArtifactFiles 产物最大文件数
     */
    public void setMaxArtifactFiles(int maxArtifactFiles) { this.maxArtifactFiles = Math.max(1, maxArtifactFiles); }

    /**
     * 获取单次执行产物总字节数上限。
     *
     * @return 单次执行产物总字节数上限
     */
    public long getMaxArtifactTotalBytes() { return maxArtifactTotalBytes; }

    /**
     * 设置单次执行产物总字节数上限。
     *
     * @param maxArtifactTotalBytes 单次执行产物总字节数上限
     */
    public void setMaxArtifactTotalBytes(long maxArtifactTotalBytes) { this.maxArtifactTotalBytes = maxArtifactTotalBytes; }

    /**
     * 获取历史保留天数。
     *
     * @return 历史保留天数
     */
    public int getRetentionHistoryDays() { return retentionHistoryDays; }

    /**
     * 设置历史保留天数，最小值 0（0 表示禁用）。
     *
     * @param v 历史保留天数
     */
    public void setRetentionHistoryDays(int v) { this.retentionHistoryDays = Math.max(0, v); }

    /**
     * 获取产物保留天数。
     *
     * @return 产物保留天数
     */
    public int getRetentionArtifactDays() { return retentionArtifactDays; }

    /**
     * 设置产物保留天数，最小值 0。
     *
     * @param v 产物保留天数
     */
    public void setRetentionArtifactDays(int v) { this.retentionArtifactDays = Math.max(0, v); }

    /**
     * 获取执行目录保留天数。
     *
     * @return 执行目录保留天数
     */
    public int getRetentionExecutionDays() { return retentionExecutionDays; }

    /**
     * 设置执行目录保留天数，最小值 0。
     *
     * @param v 执行目录保留天数
     */
    public void setRetentionExecutionDays(int v) { this.retentionExecutionDays = Math.max(0, v); }

    /**
     * 获取应用日志保留天数。
     *
     * @return 应用日志保留天数
     */
    public int getRetentionLogDays() { return retentionLogDays; }

    /**
     * 设置应用日志保留天数，最小值 0。
     *
     * @param v 应用日志保留天数
     */
    public void setRetentionLogDays(int v) { this.retentionLogDays = Math.max(0, v); }

    /**
     * 获取应用日志目录。
     *
     * @return 应用日志目录路径
     */
    public String getLogsDir() { return logsDir; }

    /**
     * 设置应用日志目录。
     *
     * @param logsDir 应用日志目录路径
     */
    public void setLogsDir(String logsDir) { this.logsDir = logsDir; }
}