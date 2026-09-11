package com.bigdata.scriptbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "scriptbox")
public class ScriptBoxProperties {
    private boolean mock = true;
    private String dataDir = "./data";
    private String scriptsDir = "./data/scripts";
    private String keytabsDir = "./data/keytabs";
    private String executionsDir = "./data/executions";
    private long maxLogBytes = 1048576L;
    private long maxScriptBytes = 1048576L;
    private long maxInputFileBytes = 10485760L; // 10 MB
    // V2: hard cap on simultaneously-running executions across the whole
    // process. Backed by a Semaphore in the executor. Defaults to 5 — generous
    // enough for normal use, low enough that runaway forks can't fill the box.
    private int maxConcurrent = 5;

    public boolean isMock() { return mock; }
    public void setMock(boolean mock) { this.mock = mock; }
    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public String getScriptsDir() { return scriptsDir; }
    public void setScriptsDir(String scriptsDir) { this.scriptsDir = scriptsDir; }
    public String getKeytabsDir() { return keytabsDir; }
    public void setKeytabsDir(String keytabsDir) { this.keytabsDir = keytabsDir; }
    public String getExecutionsDir() { return executionsDir; }
    public void setExecutionsDir(String executionsDir) { this.executionsDir = executionsDir; }
    public long getMaxLogBytes() { return maxLogBytes; }
    public void setMaxLogBytes(long maxLogBytes) { this.maxLogBytes = maxLogBytes; }
    public long getMaxScriptBytes() { return maxScriptBytes; }
    public void setMaxScriptBytes(long maxScriptBytes) { this.maxScriptBytes = maxScriptBytes; }
    public long getMaxInputFileBytes() { return maxInputFileBytes; }
    public void setMaxInputFileBytes(long maxInputFileBytes) { this.maxInputFileBytes = maxInputFileBytes; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = Math.max(1, maxConcurrent); }
}