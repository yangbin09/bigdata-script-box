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

@RestController
@RequestMapping("/api/uploads")
public class FileUploadController {

    @Autowired private FileUploadService uploadService;

    /**
     * Pre-upload a file parameter. Returns {token, originalName, absolutePath, size}
     * — the absolutePath is what the client puts into ExecutionRequest.fileInputs.
     * The script never sees a client-controlled path; the executor copies the file
     * into data/executions/{execId}/input/ at execution time.
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