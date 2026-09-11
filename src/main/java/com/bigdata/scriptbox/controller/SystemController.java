package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    @Autowired private ScriptBoxProperties props;

    @Value("${bigdata.environment-name:Mock 环境}")
    private String environmentName;

    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info() {
        Map<String, Object> data = new HashMap<>();
        data.put("mock", props.isMock());
        data.put("environmentName", environmentName);
        // The frontend uses an empty list of tenants to confirm the JAR is up.
        return ApiResponse.ok(data);
    }
}