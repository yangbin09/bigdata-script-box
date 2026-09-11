package com.bigdata.scriptbox;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
// V2: enable @Scheduled so CleanupService can run daily at the configured
// cron. Cron expression lives in scriptbox.cleanup-cron (see
// application.yml).
@EnableScheduling
@MapperScan("com.bigdata.scriptbox.mapper")
public class ScriptBoxApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScriptBoxApplication.class, args);
    }
}