package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    @Autowired private ScriptExecutor executor;
    @Autowired private com.bigdata.scriptbox.service.ResultParserService resultParserService;

    @PostMapping
    public ApiResponse<ExecutionHistory> run(@RequestBody ExecutionRequest req) {
        try {
            return ApiResponse.ok(executor.execute(req));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        } catch (IOException e) {
            return ApiResponse.error("io error: " + e.getMessage());
        }
    }

    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestBody ExecutionRequest req) {
        try {
            return ApiResponse.ok(executor.preview(req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        } catch (IOException e) {
            return ApiResponse.error("preview failed: " + e.getMessage());
        }
    }

    @GetMapping(value = "/{id}/stdout", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> stdout(@PathVariable Long id) throws IOException {
        ExecutionHistory h = executor.history(id);
        if (h == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(executor.readStdout(h));
    }

    @GetMapping(value = "/{id}/stderr", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> stderr(@PathVariable Long id) throws IOException {
        ExecutionHistory h = executor.history(id);
        if (h == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(executor.readStderr(h));
    }

    @GetMapping(value = "/{id}/result")
    public ApiResponse<Map<String, Object>> result(@PathVariable Long id) {
        ExecutionHistory h = executor.history(id);
        if (h == null) return ApiResponse.error("history not found");
        Map<String, Object> data = resultParserService.readStructured(h);
        if (data == null) return ApiResponse.error("result.json not found");
        return ApiResponse.ok(data);
    }
}