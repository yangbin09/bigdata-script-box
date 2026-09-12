package com.bigdata.scriptbox.config;

import com.bigdata.scriptbox.util.MdcContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP 请求 → MDC 注入过滤器。
 *
 * <p>从 URL / body / header 提取常用追踪字段（executionId / batchId / scenarioId），
 * 在请求处理期间注入到 SLF4J MDC，方便日志聚合工具（grep / ELK 等）按执行 /
 * 批次 / 场景切片查看日志。请求结束自动清理，避免污染线程复用导致的下一个请求。
 *
 * <p>注意：本过滤器只读取「已知安全」的字段，不会写日志敏感数据；
 * 即使 batchId / scenarioId 是攻击者伪造的，也只是日志噪音，不会带来安全风险。
 *
 * <p>执行顺序：最高优先级（{@link Ordered#HIGHEST_PRECEDENCE} + 1），
 * 确保在所有业务过滤器之前完成 MDC 设置。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@Slf4j
public class RequestMdcFilter extends OncePerRequestFilter {

    /** MDC 键名（与 ScriptExecutor.attachMdc 保持一致）。 */
    public static final String KEY_EXECUTION_ID = "executionId";
    public static final String KEY_BATCH_ID = "batchId";
    public static final String KEY_SCENARIO_ID = "scenarioId";
    public static final String KEY_REQUEST_URI = "requestUri";

    /**
     * 实际过滤逻辑：把请求上下文注入 MDC，然后放行业务过滤器。
     *
     * <p>即使下游过滤器抛异常，MDC 也会在 try-with-resources 关闭时清理，
     * 避免线程复用导致的串号。
     *
     * @param req 当前 HTTP 请求
     * @param resp 当前 HTTP 响应
     * @param chain 过滤器链
     * @throws ServletException 透传自下游
     * @throws IOException 透传自下游
     */
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        // 收集需要注入 MDC 的字段；用 MdcContext.try-with-resources 在 finally 中清理
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        map.put(KEY_REQUEST_URI, req.getRequestURI());
        String execId = req.getParameter("executionId");
        if (execId != null && !execId.isBlank()) map.put(KEY_EXECUTION_ID, execId);
        String batch = req.getParameter("batchId");
        if (batch != null && !batch.isBlank()) map.put(KEY_BATCH_ID, batch);
        String scenario = req.getParameter("scenarioId");
        if (scenario != null && !scenario.isBlank()) map.put(KEY_SCENARIO_ID, scenario);

        String[] pairs = new String[map.size() * 2];
        int idx = 0;
        for (var e : map.entrySet()) {
            pairs[idx++] = e.getKey();
            pairs[idx++] = e.getValue();
        }

        long start = System.currentTimeMillis();
        try (MdcContext ignored = MdcContext.of(pairs)) {
            chain.doFilter(req, resp);
        } finally {
            if (log.isDebugEnabled()) {
                log.debug("HTTP {} {} -> {} ({} ms)",
                        req.getMethod(), req.getRequestURI(), resp.getStatus(),
                        System.currentTimeMillis() - start);
            }
        }
    }
}