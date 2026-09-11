package com.bigdata.scriptbox.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 共享 {@link ObjectMapper} Bean 配置。
 *
 * <p>为什么需要单独的 Jackson 配置：
 * <ol>
 *   <li>Spring Boot 会自动注入一个 {@code ObjectMapper}，但本项目大量 Service
 *       仍在用 {@code new ObjectMapper()} 自行构造，导致序列化策略不一致
 *       （如 LocalDateTime 的格式）。</li>
 *   <li>本类注册的 Bean 显式开启 {@link JavaTimeModule} 并禁用
 *       WRITE_DATES_AS_TIMESTAMPS，保证 history.startTime / cleanupHistory.startTime
 *       等时间字段一律 ISO-8601 输出（前端 <code>dayjs()</code> 直接解析）。</li>
 *   <li>关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES} 防止 DTO 字段新增时反序列化炸掉。</li>
 * </ol>
 *
 * <p>调用方一律通过构造注入或 {@code @Autowired} 获取本 Bean，不再用
 * {@code new ObjectMapper()}。
 */
@Configuration
public class JacksonConfig {

    /**
     * 注册统一的 ObjectMapper Bean。
     *
     * <p>关键设置：
     * <ul>
     *   <li>注册 {@link JavaTimeModule}，支持 {@code LocalDateTime} 等 Java 8 时间类型；</li>
     *   <li>禁用时间戳序列化（统一 ISO-8601 字符串）；</li>
     *   <li>关闭「未知属性失败」以便向前兼容新增字段；</li>
     *   <li>允许空字符串视作 null 对象。</li>
     * </ul>
     *
     * @return 配置完成的 ObjectMapper
     */
    @Bean
    public ObjectMapper scriptboxObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }
}