package com.bigdata.scriptbox;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@MapperScan("com.bigdata.scriptbox.mapper")
public class ScriptBoxApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScriptBoxApplication.class, args);
    }
}