package com.bigdata.scriptbox.util;

import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MDC 上下文封装：用 try-with-resources 保证 MDC 在结束时一定被清理。
 *
 * <p>历史代码到处手动列 MDC.remove("executionId") / MDC.remove("scriptName") ...
 * 任何一个漏掉就会污染线程池复用时的下一次请求 / 下一次执行。
 *
 * <p>用法：
 * <pre>
 * try (MdcContext ignored = MdcContext.of("executionId", "123", "scriptName", "foo")) {
 *     log.info("...");
 * }
 * </pre>
 *
 * <p>关闭语义：按 put 顺序的逆序 remove（先 put 的后 remove），与 SLF4J MDC
 * 语义一致；某个 key 不存在时 {@code MDC.remove} 是 no-op，不会抛异常。
 */
public final class MdcContext implements AutoCloseable {

    private final List<String> keysInReverse;

    private MdcContext(List<String> keysInReverse) {
        this.keysInReverse = keysInReverse;
    }

    /**
     * 按 key1, value1, key2, value2 ... 的成对顺序构造 MDC 上下文。
     * 偶数长度；奇数长度抛 IllegalArgumentException。
     *
     * @param pairs 键值对（key1, value1, key2, value2, ...）
     * @return MdcContext 实例；调用方负责 close（或 try-with-resources 自动 close）
     */
    public static MdcContext of(String... pairs) {
        if (pairs == null || pairs.length % 2 != 0) {
            throw new IllegalArgumentException("MDC pairs must be even length: " + (pairs == null ? 0 : pairs.length));
        }
        // 记下所有 key + 对应原值，便于 close 时按逆序 remove
        Map<String, String> originalValues = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = pairs[i];
            String newValue = pairs[i + 1];
            // 仅在第一次见到该 key 时记录原值；后续覆盖沿用首次的原值
            if (!originalValues.containsKey(key)) {
                originalValues.put(key, MDC.get(key));
            }
            MDC.put(key, newValue);
            keys.add(key);
        }
        // 逆序：后 put 的先 remove
        List<String> reverse = new ArrayList<>(keys);
        java.util.Collections.reverse(reverse);
        return new MdcContext(reverse);
    }

    @Override
    public void close() {
        for (String key : keysInReverse) {
            try {
                MDC.remove(key);
            } catch (Exception ignored) {
                // best-effort；MDC 自身实现是 ThreadLocal.remove，不会抛
            }
        }
    }
}