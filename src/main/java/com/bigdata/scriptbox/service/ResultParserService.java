package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses optional result.json produced by a script. Format:
 *   { "status": "SUCCESS|FAILED|WARNING", "message": "...", "data": {...} }
 * The "data" object is opaque — the platform renders key/value pairs without
 * trying to interpret them. If the file is missing, malformed, or too large,
 * the script execution result is unaffected; only a log warning is emitted.
 */
@Service
public class ResultParserService {

    private static final Logger log = LoggerFactory.getLogger(ResultParserService.class);
    private static final long MAX_BYTES = 1024L * 1024L; // 1 MB cap

    @Autowired
    private com.bigdata.scriptbox.config.ScriptBoxProperties props;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Reads result.json at the given path. If it exists and is parseable, mutates
     * `history` to store the raw JSON string and overlays the status string.
     * If anything fails, logs a warning and returns without throwing.
     */
    public void parse(Path resultFile, ExecutionHistory history) {
        if (resultFile == null || history == null) return;
        if (!Files.exists(resultFile)) return;
        try {
            long size = Files.size(resultFile);
            if (size > MAX_BYTES) {
                log.warn("result.json too large ({} bytes), skipping", size);
                return;
            }
            byte[] bytes = Files.readAllBytes(resultFile);
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = mapper.readValue(text, Map.class);
            Object status = obj.get("status");
            if (status != null) {
                String s = String.valueOf(status).toUpperCase();
                if ("SUCCESS".equals(s) || "FAILED".equals(s) || "WARNING".equals(s)) {
                    // Overlay status only when script says SUCCESS, otherwise keep
                    // the executor-derived canonical value (so e.g. timeout doesn't get
                    // downgraded to WARNING).
                    if ("SUCCESS".equals(s)) history.setStatus("SUCCESS");
                }
            }
            history.setResultJson(mapper.writeValueAsString(obj));
        } catch (Exception ex) {
            log.warn("result.json parse failed for {}: {}", resultFile, ex.getMessage());
        }
    }

    /**
     * Read raw result.json for an execution history row. Returns null if absent.
     */
    public String readRaw(ExecutionHistory h) {
        if (h == null || h.getResultJsonPath() == null) return null;
        Path p = Path.of(h.getResultJsonPath());
        if (!Files.exists(p)) return null;
        try {
            long size = Files.size(p);
            if (size > MAX_BYTES) {
                return "{\"_error\":\"result.json exceeds 1MB cap\"}";
            }
            return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Parse result.json into a Map<String,Object> for the frontend. */
    public Map<String, Object> readStructured(ExecutionHistory h) {
        String raw = readRaw(h);
        if (raw == null) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = mapper.readValue(raw, Map.class);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : obj.entrySet()) {
                out.put(e.getKey(), e.getValue());
            }
            return out;
        } catch (Exception ex) {
            return null;
        }
    }
}