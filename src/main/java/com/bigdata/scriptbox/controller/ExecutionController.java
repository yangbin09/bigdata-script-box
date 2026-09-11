package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.service.RunningExecutionRegistry;
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
    @Autowired private RunningExecutionRegistry runningRegistry;

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

    /** V2: list the running executions that match a (script, tenant) pair.
     *  Used by the front-end to find the executionId for "cancel the one I'm
     *  currently waiting on", since the original POST /executions blocks
     *  until the script exits and we don't have the id yet. */
    @GetMapping("/active")
    public ApiResponse<java.util.List<Map<String, Object>>> active(
            @RequestParam(value = "scriptId", required = false) Long scriptId,
            @RequestParam(value = "tenantId", required = false) Long tenantId) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (RunningExecutionRegistry.RunningExecution re :
                runningRegistry.activeExecutions()) {
            if (scriptId != null && re.scriptId != scriptId) continue;
            if (tenantId != null && re.tenantId != tenantId) continue;
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("id", re.executionId);
            data.put("scriptId", re.scriptId);
            data.put("tenantId", re.tenantId);
            data.put("startedAtMs", re.startedAtMs);
            data.put("cancelled", re.cancelled.get());
            out.add(data);
        }
        return ApiResponse.ok(out);
    }

    /** V2: cancel a running execution by id. Idempotent — already-finished
     *  executions return "not found" rather than erroring. The cancellation
     *  happens in a background thread; the History row will be finalised with
     *  status=CANCELLED once the executor thread returns. */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id) {
        ExecutionHistory existing = executor.history(id);
        if (existing == null) {
            // Could be still-running and not yet inserted. Look in the registry.
            RunningExecutionRegistry.RunningExecution live = runningRegistry.get(id);
            if (live == null) return ApiResponse.error("execution not found");
            boolean signalled = runningRegistry.cancel(id);
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("id", id);
            data.put("cancelled", signalled);
            data.put("state", "RUNNING");
            return ApiResponse.ok(data);
        }
        // Already finalised — cannot cancel
        if (!"RUNNING".equals(existing.getStatus())) {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("id", id);
            data.put("cancelled", false);
            data.put("state", existing.getStatus());
            data.put("message", "execution already finished with status " + existing.getStatus());
            return ApiResponse.ok(data);
        }
        boolean signalled = runningRegistry.cancel(id);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", id);
        data.put("cancelled", signalled);
        data.put("state", "RUNNING");
        return ApiResponse.ok(data);
    }

    /** V2: surface the current running state for a given executionId. */
    @GetMapping("/{id}/state")
    public ApiResponse<Map<String, Object>> state(@PathVariable Long id) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", id);
        RunningExecutionRegistry.RunningExecution live = runningRegistry.get(id);
        if (live != null) {
            data.put("state", "RUNNING");
            data.put("scriptId", live.scriptId);
            data.put("tenantId", live.tenantId);
            data.put("startedAtMs", live.startedAtMs);
            data.put("cancelled", live.cancelled.get());
            return ApiResponse.ok(data);
        }
        ExecutionHistory h = executor.history(id);
        if (h == null) {
            data.put("state", "UNKNOWN");
            return ApiResponse.ok(data);
        }
        data.put("state", h.getStatus());
        data.put("exitCode", h.getExitCode());
        return ApiResponse.ok(data);
    }

    @GetMapping(value = "/{id}/stdout", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> stdout(@PathVariable Long id) throws IOException {
        ExecutionHistory h = executor.history(id);
        if (h == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(executor.readStdout(h));
    }

    /** V2: re-run an execution exactly as it ran the first time, using the
     *  snapshot stored on the history row. Sensitive env values are masked
     *  in the snapshot, and DANGEROUS scripts do not re-prompt because the
     *  original run was already authorised. */
    @PostMapping("/{id}/rerun")
    public ApiResponse<ExecutionHistory> rerun(@PathVariable Long id) {
        try {
            return ApiResponse.ok(executor.rerunFromSnapshot(id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        } catch (IOException e) {
            return ApiResponse.error("rerun failed: " + e.getMessage());
        }
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