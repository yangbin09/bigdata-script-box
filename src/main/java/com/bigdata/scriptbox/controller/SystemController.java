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

/**
 * 系统级只读接口。
 *
 * <p>当前只暴露一个 {@code /info} 端点：返回「mock 模式」开关、当前环境名等
 * 摘要信息。前端用这个接口确认后端 JAR 是否存活、并显示环境标签。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    @Autowired private ScriptBoxProperties props;

    @Value("${bigdata.environment-name:Mock 环境}")
    private String environmentName;

    /**
     * 返回系统摘要信息。
     *
     * <p>响应字段：
     * <ul>
     *   <li>{@code mock} — 当前是否 mock 模式；</li>
     *   <li>{@code environmentName} — 配置里的环境名。</li>
     * </ul>
     */
    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info() {
        Map<String, Object> data = new HashMap<>();
        data.put("mock", props.isMock());
        data.put("environmentName", environmentName);
        // 包含这两条足以让前端确认 JAR 已起来
        return ApiResponse.ok(data);
    }
}