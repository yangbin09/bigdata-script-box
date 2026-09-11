package com.bigdata.scriptbox.executor;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.service.ScriptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class ScriptExecutor {

    public ExecutionHistory history(Long id) {
        return historyMapper.selectById(id);
    }

    private static final Logger log = LoggerFactory.getLogger(ScriptExecutor.class);

    @Autowired private ScriptBoxProperties props;
    @Autowired private ScriptService scriptService;
    @Autowired private ExecutionHistoryMapper historyMapper;
    @Autowired private com.bigdata.scriptbox.service.TenantService tenantService;
    @Autowired private com.bigdata.scriptbox.service.GlobalVariableService globalVariableService;
    @Autowired private com.bigdata.scriptbox.service.PresetService presetService;
    @Autowired private com.bigdata.scriptbox.service.ResultParserService resultParserService;
    @Autowired private com.bigdata.scriptbox.service.FileUploadService fileUploadService;
    @Autowired private com.bigdata.scriptbox.service.PrecheckService precheckService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.atomic.AtomicLong counter = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() * 1000L);

    public ExecutionHistory execute(ExecutionRequest req) throws IOException {
        Script script = scriptService.getById(req.getScriptId());
        if (script == null) throw new IllegalArgumentException("script not found: " + req.getScriptId());
        if (script.getEnabled() == null || !script.getEnabled())
            throw new IllegalArgumentException("script is disabled: " + script.getName());

        Tenant tenant = tenantService.getById(req.getTenantId());
        if (tenant == null) throw new IllegalArgumentException("tenant not found: " + req.getTenantId());
        if (tenant.getEnabled() == null || !tenant.getEnabled())
            throw new IllegalArgumentException("tenant is disabled: " + tenant.getName());

        List<ScriptParam> params = scriptService.paramsOf(script.getId());
        // If a preset is referenced, its values override any supplied params.
        Map<String, String> validated;
        if (req.getPresetId() != null) {
            var preset = presetService.get(script.getId(), req.getPresetId());
            if (preset == null) throw new IllegalArgumentException("preset not found: " + req.getPresetId());
            Map<String, String> fromPreset = presetService.applyParams(preset);
            Map<String, String> supplied = req.getParams() == null ? Map.of() : req.getParams();
            Map<String, String> merged = new LinkedHashMap<>(fromPreset);
            merged.putAll(supplied); // supplied params win over preset defaults
            validated = validateAndCoerce(params, merged);
        } else {
            validated = validateAndCoerce(params, req.getParams() == null ? Map.of() : req.getParams());
        }

        long executionId = nextExecutionId();
        Path execDir = Paths.get(props.getExecutionsDir(), String.valueOf(executionId));
        Files.createDirectories(execDir);

        // Promote any file-type inputs into the exec directory so the shell
        // receives the safe, server-controlled path. Done before building the
        // command list so we never expose a client-supplied path to the script.
        if (req.getFileInputs() != null && !req.getFileInputs().isEmpty()) {
            Map<String, String> resolved = fileUploadService.promoteForExecution(executionId, req.getFileInputs());
            for (Map.Entry<String, String> e : resolved.entrySet()) {
                validated.put(e.getKey(), e.getValue());
            }
        }

        Path stdoutFile = execDir.resolve("stdout.log");
        Path stderrFile = execDir.resolve("stderr.log");
        Path resultFile = execDir.resolve("result.json");

        ExecutionHistory history = new ExecutionHistory();
        history.setScriptId(script.getId());
        history.setScriptName(script.getName());
        history.setTenantId(tenant.getId());
        history.setTenantName(tenant.getName());
        history.setParametersJson(mapper.writeValueAsString(validated));
        history.setTimeout(Boolean.FALSE);
        history.setStartTime(LocalDateTime.now());
        history.setStdoutPath(stdoutFile.toAbsolutePath().toString());
        history.setStderrPath(stderrFile.toAbsolutePath().toString());
        history.setExecutionDir(execDir.toAbsolutePath().toString());
        history.setResultJsonPath(resultFile.toAbsolutePath().toString());
        if (req.getBatchId() != null) {
            history.setBatchId(req.getBatchId());
            history.setBatchRowIndex(req.getBatchRowIndex());
        }
        if (req.getScenarioId() != null) {
            history.setScenarioId(req.getScenarioId());
            history.setScenarioStepNo(req.getScenarioStepNo());
        }

        // Pre-execution checks (only if the script has precheck config).
        Map<String, Object> precheck = precheckService.run(script, tenant);
        if (!Boolean.TRUE.equals(precheck.get("ok"))) {
            history.setEndTime(LocalDateTime.now());
            history.setDurationMs(0L);
            history.setExitCode(-1);
            history.setTimeout(false);
            history.setSuccess(false);
            history.setStatus("PRECHECK_FAILED");
            historyMapper.insert(history);
            return history;
        }

        String scriptPath = script.getScriptPath();
        if (scriptPath == null || !Files.exists(Paths.get(scriptPath)))
            throw new IllegalStateException("script file missing on disk: " + scriptPath);
        if (!Paths.get(scriptPath).toAbsolutePath().startsWith(Paths.get(props.getScriptsDir()).toAbsolutePath()))
            throw new IllegalStateException("script path escapes scripts dir");

        List<String> command = buildCommand(scriptPath, validated);
        log.info("exec executionId={} script={} tenant={} cmd={}",
                executionId, script.getName(), tenant.getName(), command);

        long startMs = System.currentTimeMillis();
        int timeoutSeconds = script.getTimeoutSeconds() == null ? 600 : script.getTimeoutSeconds();

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(execDir.toFile());
        pb.redirectErrorStream(false);
        // Inject global variables into ProcessBuilder environment (priority lower than preset/params,
        // higher than defaultValue). Apply them here BEFORE the kinit wrapper is composed below.
        pb.environment().putAll(globalVariableService.envForExecution());

        // In mock mode, ensure the dev box without Hadoop can still run; no kinit wrapper.
        if (!props.isMock() && tenant.getKeytabPath() != null && !tenant.getKeytabPath().isBlank()) {
            // Real mode: wrap the call in `kinit`-then-run sequence using a small bootstrap.
            // We achieve this by writing a tiny wrapper .sh that kinit's then exec's the target.
            Path wrapper = execDir.resolve("_kinit_wrap.sh");
            Files.writeString(wrapper,
                    "#!/usr/bin/env bash\nset -e\n" +
                    "kinit -kt '" + tenant.getKeytabPath().replace("'", "'\\''") + "' '" +
                    tenant.getPrincipal().replace("'", "'\\''") + "' || exit 127\n" +
                    "exec \"" + scriptPath + "\" \"$@\"\n",
                    StandardCharsets.UTF_8);
            new File(wrapper.toString()).setExecutable(true);
            List<String> withKinit = new ArrayList<>();
            withKinit.add("bash");
            withKinit.add(wrapper.toString());
            withKinit.addAll(validated.isEmpty() ? List.of() : buildArgsFromMap(validated));
            pb = new ProcessBuilder(withKinit);
            pb.directory(execDir.toFile());
            pb.redirectErrorStream(false);
            pb.environment().putAll(globalVariableService.envForExecution());
        }

        Process process = pb.start();

        // Drain stdout and stderr concurrently to avoid pipe-buffer deadlock.
        Thread drainOut = drainAsync(process.getInputStream(), stdoutFile, "stdout");
        Thread drainErr = drainAsync(process.getErrorStream(), stderrFile, "stderr");

        boolean finished;
        boolean timedOut = false;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            timedOut = true;
            process.destroyForcibly();
        }

        try {
            drainOut.join(2000);
            drainErr.join(2000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        long duration = System.currentTimeMillis() - startMs;
        int exitCode;
        try {
            exitCode = process.exitValue();
        } catch (IllegalThreadStateException ex) {
            exitCode = -1;
        }
        if (timedOut) exitCode = -1;

        history.setEndTime(LocalDateTime.now());
        history.setDurationMs(duration);
        history.setExitCode(exitCode);
        history.setTimeout(timedOut);
        boolean ok = !timedOut && exitCode == 0;
        history.setSuccess(ok);
        // Canonical status vocabulary: SUCCESS / FAILED / TIMEOUT
        String status = timedOut ? "TIMEOUT" : (ok ? "SUCCESS" : "FAILED");
        history.setStatus(status);
        // Best-effort parse of result.json after the run; failure is non-fatal.
        try {
            if (Files.exists(resultFile)) {
                resultParserService.parse(resultFile, history);
            }
        } catch (Exception ex) {
            log.warn("result.json parse failed for executionId={}: {}", executionId, ex.getMessage());
        }

        historyMapper.insert(history);
        return history;
    }

    /**
     * Build a "dry run" preview: the resolved command list, environment map (sensitive
     * entries masked), and effective parameter values. Does NOT spawn a process.
     */
    public Map<String, Object> preview(ExecutionRequest req) throws IOException {
        Script script = scriptService.getById(req.getScriptId());
        if (script == null) throw new IllegalArgumentException("script not found: " + req.getScriptId());
        Tenant tenant = tenantService.getById(req.getTenantId());
        if (tenant == null) throw new IllegalArgumentException("tenant not found: " + req.getTenantId());

        List<ScriptParam> params = scriptService.paramsOf(script.getId());
        Map<String, String> validated;
        if (req.getPresetId() != null) {
            var preset = presetService.get(script.getId(), req.getPresetId());
            if (preset == null) throw new IllegalArgumentException("preset not found: " + req.getPresetId());
            Map<String, String> fromPreset = presetService.applyParams(preset);
            Map<String, String> supplied = req.getParams() == null ? Map.of() : req.getParams();
            Map<String, String> merged = new LinkedHashMap<>(fromPreset);
            merged.putAll(supplied);
            validated = validateAndCoerce(params, merged);
        } else {
            validated = validateAndCoerce(params, req.getParams() == null ? Map.of() : req.getParams());
        }

        String scriptPath = script.getScriptPath();
        List<String> command;
        boolean kinitWrap = !props.isMock() && tenant.getKeytabPath() != null && !tenant.getKeytabPath().isBlank();
        if (kinitWrap) {
            command = new ArrayList<>();
            command.add("bash");
            command.add("<executions-dir>/<exec-id>/_kinit_wrap.sh");
            command.addAll(buildArgsFromMap(validated));
        } else {
            command = buildCommand(scriptPath, validated);
        }

        // Mask sensitive variables in env.
        Map<String, String> rawEnv = globalVariableService.envForExecution();
        Map<String, String> masked = new LinkedHashMap<>(rawEnv);
        for (var v : globalVariableService.listEnabled()) {
            if (Boolean.TRUE.equals(v.getSensitive()) && masked.containsKey(v.getVariableKey())) {
                masked.put(v.getVariableKey(), "******");
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scriptId", script.getId());
        out.put("scriptName", script.getName());
        out.put("scriptDisplayName", script.getDisplayName());
        out.put("scriptPath", scriptPath);
        out.put("tenantId", tenant.getId());
        out.put("tenantName", tenant.getName());
        out.put("principal", tenant.getPrincipal());
        out.put("timeoutSeconds", script.getTimeoutSeconds());
        out.put("enabled", script.getEnabled());
        out.put("params", validated);
        out.put("command", command);
        out.put("kinitWrapped", kinitWrap);
        out.put("globalVariables", masked);
        out.put("keytabSet", tenant.getKeytabPath() != null && !tenant.getKeytabPath().isBlank());
        // For preview we mask keytab path completely.
        out.put("keytabPath", tenant.getKeytabPath() != null && !tenant.getKeytabPath().isBlank() ? "******" : null);
        return out;
    }

    public byte[] readStdout(ExecutionHistory h) throws IOException {
        return readUpTo(h.getStdoutPath(), props.getMaxLogBytes());
    }

    public byte[] readStderr(ExecutionHistory h) throws IOException {
        return readUpTo(h.getStderrPath(), props.getMaxLogBytes());
    }

    private byte[] readUpTo(String path, long max) throws IOException {
        if (path == null) return new byte[0];
        File f = new File(path);
        if (!f.exists()) return new byte[0];
        long len = f.length();
        if (len <= max) return Files.readAllBytes(Paths.get(path));
        // Read only the trailing max bytes
        try (var ch = Files.newByteChannel(Paths.get(path))) {
            ch.position(len - max);
            var buf = java.nio.ByteBuffer.allocate((int) max);
            ch.read(buf);
            buf.flip();
            // prefix marker
            byte[] tail = new byte[buf.remaining() + 64];
            String prefix = "--- truncated, showing last " + max + " bytes ---\n";
            byte[] pb = prefix.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(pb, 0, tail, 0, pb.length);
            buf.get(tail, pb.length, buf.remaining());
            return java.util.Arrays.copyOf(tail, pb.length + buf.remaining());
        }
    }

    private long nextExecutionId() {
        return counter.incrementAndGet();
    }

    private List<String> buildCommand(String scriptPath, Map<String, String> params) {
        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add(scriptPath);
        cmd.addAll(buildArgsFromMap(params));
        return cmd;
    }

    private List<String> buildArgsFromMap(Map<String, String> params) {
        List<String> args = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            args.add("--" + e.getKey());
            args.add(e.getValue());
        }
        return args;
    }

    private Thread drainAsync(java.io.InputStream in, Path target, String label) {
        Thread t = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                 var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                }
            } catch (IOException e) {
                log.warn("drain {} failed: {}", label, e.getMessage());
            }
        }, "exec-drain-" + label);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Validate user input against the script's declared params, coerce to expected types,
     * and apply defaults. Returns the final map of values to pass as --key value to the script.
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
                // booleans always have a value
            } else if (Boolean.TRUE.equals(p.getRequired()) && (value == null || value.isBlank())) {
                throw new IllegalArgumentException("param '" + name + "' is required");
            }
            if (value == null) value = "";
            String type = p.getType() == null ? "text" : p.getType().toLowerCase();
            switch (type) {
                case "number":
                    if (!value.isBlank()) {
                        try { Double.parseDouble(value); }
                        catch (NumberFormatException ex) {
                            throw new IllegalArgumentException("param '" + name + "' must be a number");
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
                                "param '" + name + "' must be one of: " + p.getOptions());
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
                    // raw string
                    break;
            }
            out.put(name, value);
        }
        // ignore extra params
        return out;
    }
}