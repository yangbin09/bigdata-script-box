package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析脚本可选输出的 result.json。格式约定：
 * <pre>{@code
 *   { "status": "SUCCESS|FAILED|WARNING", "message": "...", "data": {...} }
 * }</pre>
 *
 * <p>"data" 段是不透明对象 —— 平台原样展示 key/value，不尝试解释。
 * 文件缺失 / 格式错误 / 过大都不会影响脚本执行的最终结论，仅 WARN 日志告警。
 */
@Service
public class ResultParserService {

    private static final Logger log = LoggerFactory.getLogger(ResultParserService.class);

    /** 单文件 1 MB 硬上限，超出视为脚本异常输出，不再尝试解析。 */
    private static final long MAX_BYTES = 1024L * 1024L;

    private final ObjectMapper mapper;

    public ResultParserService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 读取 result.json；若存在且可解析，把原始 JSON 串写入
     * {@code history.resultJson}，并按脚本的 status 字段覆盖执行器写入的 status
     * （仅 SUCCESS 才覆盖，避免把 timeout 错误降级为 WARNING）。
     *
     * <p>任何异常只 WARN 不会抛 —— result.json 是脚本侧的副产物，不应干扰主流程。
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
                    // 只有当脚本显式声明 SUCCESS 才覆盖执行器写下的状态；
                    // timeout / cancelled 等不应被 result.json 降级成 WARNING。
                    if ("SUCCESS".equals(s)) history.setStatus("SUCCESS");
                }
            }
            history.setResultJson(mapper.writeValueAsString(obj));
            log.info("result.json: 已解析 historyId={} size={} bytes", history.getId(), size);
        } catch (Exception ex) {
            log.warn("result.json parse failed for {}: {}", resultFile, ex.getMessage());
        }
    }

    /**
     * 读取 result.json 原始字符串。文件不存在或过大返回 null / 错误占位串。
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

    /** 把 result.json 解析成 Map 给前端展示；解析失败返回 null。 */
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