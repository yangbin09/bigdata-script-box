package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.service.FileUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 文件参数上传接口。
 *
 * <p>提供两步式文件参数流的第一步：把上传文件暂存到
 * {@code ./data/uploads/<token>/}，返回 {@code token} 与 {@code absolutePath}。
 * 执行时由 {@link FileUploadService#promoteForExecution} 把文件复制到
 * 执行目录的 {@code input/} 子目录下。
 *
 * <p>脚本拿到的路径由后端决定，前端不能传入任意服务器路径。
 */
@RestController
@RequestMapping("/api/uploads")
public class FileUploadController {

    @Autowired private FileUploadService uploadService;

    /**
     * 暂存上传文件，返回后续执行所需的 token 与路径。
     *
     * @param file 上传文件
     * @return {token, originalName, absolutePath, size}
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.ok(uploadService.savePending(file));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        } catch (IOException ex) {
            return ApiResponse.error("upload failed: " + ex.getMessage());
        }
    }
}