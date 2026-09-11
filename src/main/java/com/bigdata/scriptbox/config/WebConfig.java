package com.bigdata.scriptbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA 路由回退配置。
 *
 * <p>对于非 API 的浏览器路径（Vue Router 默认使用 hash 模式，但刷新或外链
 * 直接进入子路由时仍需干净的 URL），统一回退到 {@code index.html}。
 *
 * <p>静态资源（JS / CSS / 图片）由 Spring Boot 默认的 ResourceHandler 从
 * {@code classpath:/static/} 提供，而该目录正是 Vite 编译产物的输出位置。
 *
 * <p>{@code forward:/index.html} 的好处：让静态资源处理器继续负责返回真实文件，
 * 同时保证 Vue Router 收到正确的 HTML 入口。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 注册需要回退到 SPA 入口的视图控制器。
     *
     * @param registry Spring MVC 视图控制器注册表
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/scripts").setViewName("forward:/index.html");
        registry.addViewController("/scripts/edit").setViewName("forward:/index.html");
        registry.addViewController("/tenants").setViewName("forward:/index.html");
        registry.addViewController("/history").setViewName("forward:/index.html");
    }
}