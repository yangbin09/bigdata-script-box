package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ScriptVersion;
import com.bigdata.scriptbox.service.ScriptVersionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本版本接口。
 *
 * <p>每次保存脚本正文都会落一条 {@link ScriptVersion}；本组接口提供：
 * <ul>
 *   <li>按脚本列出全部版本；</li>
 *   <li>查单个版本；</li>
 *   <li>回滚到指定版本（实际是「复制旧版本内容再保存一次」，产生新版本号）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/scripts/{scriptId}/versions")
public class ScriptVersionController {

    @Autowired private ScriptVersionService versionService;

    /**
     * 列出指定脚本的全部版本（按 version_no 降序）。
     *
     * @param scriptId 脚本 ID
     * @return 版本列表
     */
    @GetMapping
    public ApiResponse<List<ScriptVersion>> list(@PathVariable Long scriptId) {
        return ApiResponse.ok(versionService.listByScript(scriptId));
    }

    /**
     * 查询某个版本号对应的版本详情。
     *
     * @param scriptId 脚本 ID
     * @param versionNo 版本号
     * @return 版本详情
     */
    @GetMapping("/{versionNo}")
    public ApiResponse<ScriptVersion> get(@PathVariable Long scriptId, @PathVariable Integer versionNo) {
        ScriptVersion v = versionService.get(scriptId, versionNo);
        if (v == null) return ApiResponse.error("version not found");
        return ApiResponse.ok(v);
    }

    /**
     * 回滚到指定版本：把旧版本内容重新保存为新版本。原始版本不会被覆盖。
     *
     * @param scriptId 脚本 ID
     * @param versionNo 目标版本号
     * @return 新版本实体（包在 {@code newVersion} 字段里）
     */
    @PostMapping("/{versionNo}/rollback")
    public ApiResponse<Map<String, Object>> rollback(@PathVariable Long scriptId,
                                                     @PathVariable Integer versionNo) {
        try {
            ScriptVersion created = versionService.rollback(scriptId, versionNo);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("newVersion", created);
            return ApiResponse.ok(out);
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        } catch (Exception ex) {
            return ApiResponse.error("rollback failed: " + ex.getMessage());
        }
    }
}