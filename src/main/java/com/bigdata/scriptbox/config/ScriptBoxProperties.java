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
}