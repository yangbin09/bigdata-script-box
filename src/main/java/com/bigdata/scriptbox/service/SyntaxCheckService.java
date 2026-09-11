package com.bigdata.scriptbox.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V2: enforce that a script body parses cleanly with {@code bash -n} before
 * we let it be persisted. {@code bash -n} only checks syntax — it doesn't
 * execute anything — so it's safe to run on user-supplied code.
 *
 * <p>{@code shellcheck} is also wired in but optional; it produces richer
 * diagnostics (unused vars, quoting, etc.) but requires the {@code shellcheck}
 * binary to be on PATH. When missing we silently skip and return only the
 * bash -n result.
 */
@Service
public class SyntaxCheckService {

    private static final Logger log = LoggerFactory.getLogger(SyntaxCheckService.class);

    public static class SyntaxResult {
        public final boolean ok;
        public final List<String> errors;
        public final List<String> warnings;
        public SyntaxResult(boolean ok, List<String> errors, List<String> warnings) {
            this.ok = ok;
            this.errors = errors;
            this.warnings = warnings;
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", ok);
            m.put("errors", errors);
            m.put("warnings", warnings);
            return m;
        }
    }

    /**
     * Run a syntax check on the supplied body. Writes to a temp file under
     * {@code java.io.tmpdir}, invokes {@code bash -n <file>}, parses the
     * stderr (bash reports file:line:col: message), and returns a structured
     * result. Throws nothing — syntax errors are returned, not raised.
     */
    public SyntaxResult check(String body) {
        if (body == null) body = "";
        Path tmp;
        try {
            tmp = Files.createTempFile("scriptbox-syntax-", ".sh");
        } catch (IOException e) {
            throw new IllegalStateException("cannot create temp file: " + e.getMessage(), e);
        }
        try {
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            int code = runProcess(List.of("bash", "-n", tmp.toAbsolutePath().toString()),
                    errors, /* timeoutMs */ 5000);
            // bash -n exit 0 means clean. Anything else is a syntax error.
            boolean ok = (code == 0) && errors.isEmpty();
            if (ok) {
                // Try shellcheck opportunistically; ignore if absent.
                tryShellcheck(tmp, warnings);
            }
            return new SyntaxResult(ok, errors, warnings);
        } catch (IOException ioe) {
            throw new IllegalStateException("syntax check IO error: " + ioe.getMessage(), ioe);
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private int runProcess(List<String> command, List<String> stderrLines, long timeoutMs) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            Thread drainOut = new Thread(() -> {
                try (var br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    while (br.readLine() != null) { /* discard */ }
                } catch (IOException ignored) {}
            }, "syntaxcheck-drain-out");
            Thread drainErr = new Thread(() -> {
                try (var br = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        stderrLines.add(line.trim());
                    }
                } catch (IOException ignored) {}
            }, "syntaxcheck-drain-err");
            drainOut.setDaemon(true);
            drainErr.setDaemon(true);
            drainOut.start();
            drainErr.start();
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                stderrLines.add("bash -n timed out after " + timeoutMs + "ms");
                return -1;
            }
            drainOut.join(1000);
            drainErr.join(1000);
            return p.exitValue();
        } catch (IOException | InterruptedException e) {
            stderrLines.add("bash -n failed: " + e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void tryShellcheck(Path file, List<String> warnings) {
        // shellcheck is optional; if missing, skip silently.
        try {
            ProcessBuilder pb = new ProcessBuilder("shellcheck", "-S", "warning", file.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (var br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append('\n');
            }
            if (!p.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return;
            }
            int code = p.exitValue();
            if (code == 0) return;
            // shellcheck exited non-zero — diagnostics on stdout (we merged stderr).
            for (String line : out.toString().split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                warnings.add(t);
            }
        } catch (IOException ioe) {
            // shellcheck not installed; that's fine.
            log.debug("shellcheck unavailable: {}", ioe.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}