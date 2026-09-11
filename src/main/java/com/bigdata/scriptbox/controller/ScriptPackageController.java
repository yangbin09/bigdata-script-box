package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.service.ScriptPackageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 脚本包导入/导出接口。
 *
 * <p>把单个脚本（正文 + 参数列表 + 元数据）打包成 zip 上传/下载，便于跨环境
 * 拷贝。导出时附带 {@code Content-Disposition: attachment;} 头供浏览器保存。
 */
@RestController
@RequestMapping("/api/scripts")
public class ScriptPackageController {

    @Autowired private ScriptPackageService packageService;

    /**
     * 导出指定脚本为 zip 包。
     *
     * <p>找不到脚本返回 404，IO 错误返回 500。
     *
     * @param id 脚本 ID
     * @return zip 字节流
     */
    @GetMapping(value = "/{id}/export", produces = "application/zip")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        try {
            byte[] bytes = packageService.export(id);
            // 优先使用服务给定的导出文件名，回退到默认
            String name = "script-" + id + ".zip";
            try {
                name = packageService.exportFilename(id);
            } catch (Exception ignored) {}
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + name + "\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(bytes);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        } catch (IOException ex) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 从 zip 包导入脚本。请求参数：
     * <ul>
     *   <li>{@code file} — zip 文件；</li>
     *   <li>{@code overwrite} — 若脚本名已存在是否覆盖（默认 false）。</li>
     * </ul>
     *
     * @param file zip 文件
     * @param overwrite 是否覆盖同名脚本
     * @return 导入结果（成功 / 失败列表）
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ScriptPackageService.ImportResult> doImport(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "overwrite", required = false, defaultValue = "false") boolean overwrite) {
        try {
            return ApiResponse.ok(packageService.doImport(file, overwrite));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        } catch (IOException ex) {
            return ApiResponse.error("import failed: " + ex.getMessage());
        }
    }
}