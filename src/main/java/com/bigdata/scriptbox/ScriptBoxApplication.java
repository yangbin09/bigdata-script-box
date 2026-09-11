package com.bigdata.scriptbox;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * BigData Script Box 应用启动入口。
 *
 * <p>负责装配 Spring Boot 容器、启用异步任务支持（{@link EnableAsync}）以及
 * MyBatis-Plus Mapper 扫描。
 *
 * <p>V2 设计要点：清理功能完全手动化，<b>不</b>引入 {@code @EnableScheduling}，
 * 也<b>不</b>注册任何 {@code @Scheduled} 定时任务。BigData Script Box 不会
 * 自动删除任何数据，所有删除都必须走「预览 → 确认 → 执行」的人工流程。
 *
 * @author bigdata-script-box
 */
@SpringBootApplication
@EnableAsync
@MapperScan("com.bigdata.scriptbox.mapper")
public class ScriptBoxApplication {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行参数，会被 Spring Boot 解析为配置源（如 --server.port）
     */
    public static void main(String[] args) {
        SpringApplication.run(ScriptBoxApplication.class, args);
    }
}