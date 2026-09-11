package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-execution environment checks. Reads JSON config from
 * Script.precheckConfigJson and runs a series of checks before launching the
 * actual script. Each check returns {ok, message} and is independent.
 *
 * Supported keys in the JSON:
 *   {
 *     "kerberos":              true|false,
 *     "commands":   ["spark-sql", ...],
 *     "files":      ["/opt/client/bigdata_env", ...],
 *     "writableDirectories": ["/tmp/bigdata-script-box", ...]
 *   }
 *
 * Any missing / unknown key is silently ignored, so an empty config means
 * "no checks". Scripts with no precheck config skip this phase entirely.
 */
@Service
public class PrecheckService {

    private static final Logger log = LoggerFactory.getLogger(PrecheckService.class);

    @Autowired private ScriptBoxProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public static class CheckResult {
        public String name;       // "Kerberos", "command:spark-sql", "file:..."
        public boolean ok;
        public String message;
        public CheckResult() {}
        public CheckResult(String name, boolean ok, String message) {
            this.name = name; this.ok = ok; this.message = message;
        }
    }

    public Map<String, Object> run(Script script, Tenant tenant) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        summary.put("results", results);
        boolean allOk = true;

        if (script.getPrecheckConfigJson() == null || script.getPrecheckConfigJson().isBlank()) {
            summary.put("ok", true);
            summary.put("skipped", true);
            summary.put("message", "no precheck configured");
            return summary;
        }
        Map<String, Object> cfg;
        try {
            cfg = mapper.readValue(script.getPrecheckConfigJson(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            summary.put("ok", false);
            summary.put("skipped", false);
            summary.put("message", "invalid precheck config: " + ex.getMessage());
            return summary;
        }

        // Kerberos
        if (Boolean.TRUE.equals(cfg.get("kerberos"))) {
            CheckResult r = checkKerberos(tenant);
            results.add(toMap(r));
            if (!r.ok) allOk = false;
        }
        // Commands exist on PATH (or absolute path)
        @SuppressWarnings("unchecked")
        List<String> commands = (List<String>) cfg.getOrDefault("commands", List.of());
        for (String cmd : commands) {
            CheckResult r = checkCommand(cmd);
            results.add(toMap(r));
            if (!r.ok) allOk = false;
        }
        // Files exist
        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) cfg.getOrDefault("files", List.of());
        for (String f : files) {
            CheckResult r = checkFile(f);
            results.add(toMap(r));
            if (!r.ok) allOk = false;
        }
        // Directories writable
        @SuppressWarnings("unchecked")
        List<String> dirs = (List<String>) cfg.getOrDefault("writableDirectories", List.of());
        for (String d : dirs) {
            CheckResult r = checkWritableDir(d);
            results.add(toMap(r));
            if (!r.ok) allOk = false;
        }
        summary.put("ok", allOk);
        summary.put("skipped", false);
        summary.put("message", allOk ? "all checks passed" : "one or more checks failed");
        return summary;
    }

    private static Map<String, Object> toMap(CheckResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.name);
        m.put("ok", r.ok);
        m.put("message", r.message);
        return m;
    }

    private CheckResult checkKerberos(Tenant tenant) {
        if (tenant == null) return new CheckResult("Kerberos", false, "tenant not set");
        if (!Boolean.TRUE.equals(tenant.getEnabled()))
            return new CheckResult("Kerberos", false, "tenant disabled: " + tenant.getName());
        if (tenant.getPrincipal() == null || tenant.getPrincipal().isBlank())
            return new CheckResult("Kerberos", false, "tenant has no principal");
        if (!props.isMock()) {
            if (tenant.getKeytabPath() == null || tenant.getKeytabPath().isBlank())
                return new CheckResult("Kerberos", false, "keytab not configured");
            if (!Files.exists(Paths.get(tenant.getKeytabPath())))
                return new CheckResult("Kerberos", false, "keytab file missing");
        }
        return new CheckResult("Kerberos", true, "tenant=" + tenant.getName() + " principal=" + tenant.getPrincipal());
    }

    private CheckResult checkCommand(String cmd) {
        if (cmd == null || cmd.isBlank())
            return new CheckResult("command:<empty>", false, "empty command name");
        // Absolute path: just check exists + executable
        Path p = Paths.get(cmd);
        if (p.isAbsolute()) {
            if (Files.exists(p) && Files.isExecutable(p))
                return new CheckResult("command:" + cmd, true, "absolute path exists and executable");
            return new CheckResult("command:" + cmd, false, "not executable");
        }
        // Walk PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) pathEnv = "/usr/local/bin:/usr/bin:/bin";
        for (String dir : pathEnv.split(":")) {
            Path candidate = Paths.get(dir, cmd);
            if (Files.exists(candidate) && Files.isExecutable(candidate))
                return new CheckResult("command:" + cmd, true, "found in " + dir);
        }
        return new CheckResult("command:" + cmd, false, "not found in PATH");
    }

    private CheckResult checkFile(String f) {
        if (f == null || f.isBlank())
            return new CheckResult("file:<empty>", false, "empty path");
        Path p = Paths.get(f);
        if (!Files.exists(p))
            return new CheckResult("file:" + f, false, "file does not exist");
        return new CheckResult("file:" + f, true, "exists");
    }

    private CheckResult checkWritableDir(String d) {
        if (d == null || d.isBlank())
            return new CheckResult("dir:<empty>", false, "empty path");
        Path p = Paths.get(d);
        try {
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (IOException ex) {
            return new CheckResult("dir:" + d, false, "cannot create: " + ex.getMessage());
        }
        if (!Files.isDirectory(p))
            return new CheckResult("dir:" + d, false, "not a directory");
        if (!Files.isWritable(p))
            return new CheckResult("dir:" + d, false, "not writable");
        return new CheckResult("dir:" + d, true, "writable");
    }
}