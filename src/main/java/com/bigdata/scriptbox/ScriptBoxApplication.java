package com.bigdata.scriptbox;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// V2: cleanup is fully manual. No @EnableScheduling, no @Scheduled cron,
// no startup-triggered cleanup. BigData Script Box must never delete data
// on its own — all deletes go through a user-driven preview/confirm flow.
@SpringBootApplication
@EnableAsync
@MapperScan("com.bigdata.scriptbox.mapper")
public class ScriptBoxApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScriptBoxApplication.class, args);
    }
}