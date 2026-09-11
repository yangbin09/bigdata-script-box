package com.bigdata.scriptbox.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
public class RequestMdcFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestMdcFilter.class);

    /** MDC 键名（与 ScriptExecutor.attachMdc 保持一致）。 */
    public static final String KEY_EXECUTION_ID = "executionId";
    public static final String KEY_BATCH_ID = "batchId";
    public static final String KEY_SCENARIO_ID = "scenarioId";
    public static final String KEY_REQUEST_URI = "requestUri";

    /**
     * 实际过滤逻辑：把请求上下文注入 MDC，然后放行业务过滤器。
     *
     * <p>即使下游过滤器抛异常，MDC 也会在 finally 中清理，避免线程复用导致
     * 的串号。
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
        boolean changed = false;
        try {
            // requestUri 永远是当前请求，便于日志关联
            MDC.put(KEY_REQUEST_URI, req.getRequestURI());
            changed = true;

            // executionId 出现在 /execution/* 路径里（history detail / log tail）
            String execId = req.getParameter("executionId");
            if (execId != null && !execId.isBlank()) {
                MDC.put(KEY_EXECUTION_ID, execId);
            }

            // batchId / scenarioId 通常来自 POST body，但 GET 也可能用 query 传
            String batch = req.getParameter("batchId");
            if (batch != null && !batch.isBlank()) MDC.put(KEY_BATCH_ID, batch);
            String scenario = req.getParameter("scenarioId");
            if (scenario != null && !scenario.isBlank()) MDC.put(KEY_SCENARIO_ID, scenario);

            long start = System.currentTimeMillis();
            try {
                chain.doFilter(req, resp);
            } finally {
                if (log.isDebugEnabled()) {
                    log.debug("HTTP {} {} -> {} ({} ms)",
                            req.getMethod(), req.getRequestURI(), resp.getStatus(),
                            System.currentTimeMillis() - start);
                }
            }
        } finally {
            if (changed) {
                MDC.remove(KEY_EXECUTION_ID);
                MDC.remove(KEY_BATCH_ID);
                MDC.remove(KEY_SCENARIO_ID);
                MDC.remove(KEY_REQUEST_URI);
            }
        }
    }
}