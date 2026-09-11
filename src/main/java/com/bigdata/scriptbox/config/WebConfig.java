package com.bigdata.scriptbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA fallback — for non-API browser routes (the Vue Router uses hash history, but
 * we still want clean URLs to serve index.html when refreshed or linked directly).
 *
 * Static resources (JS/CSS/images) are served by Spring Boot's default ResourceHandler
 * from classpath:/static/, which is where the Vite build outputs are copied.
 *
 * The forward:/index.html approach lets the static resource handler serve the actual file.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/scripts").setViewName("forward:/index.html");
        registry.addViewController("/scripts/edit").setViewName("forward:/index.html");
        registry.addViewController("/tenants").setViewName("forward:/index.html");
        registry.addViewController("/history").setViewName("forward:/index.html");
    }
}