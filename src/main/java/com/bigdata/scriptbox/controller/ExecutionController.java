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

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    @Autowired private ScriptExecutor executor;

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
}