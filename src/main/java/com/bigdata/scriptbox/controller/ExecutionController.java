package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.service.RunningExecutionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * 脚本执行相关接口。
 *
 * <p>提供：
 * <ul>
 *   <li>POST /api/executions 同步执行脚本（阻塞到完成）；</li>
 *   <li>POST /api/executions/preview 预演（不真启动进程）；</li>
 *   <li>GET /api/executions/active 查询正在运行的执行；</li>
 *   <li>POST /api/executions/{id}/cancel 取消运行；</li>
 *   <li>GET /api/executions/{id}/state 查询执行状态；</li>
 *   <li>GET /api/executions/{id}/stdout|stderr 日志查看；</li>
 *   <li>POST /api/executions/{id}/rerun 按快照重放；</li>
 *   <li>GET /api/executions/{id}/result 解析 result.json；</li>
 *   <li>GET /api/executions/{id}/artifacts[/{name}] 产物列表 / 下载。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/executions")
@Slf4j
@RequiredArgsConstructor
public class ExecutionController {

    private final ScriptExecutor executor;
    private final com.bigdata.scriptbox.service.ResultParserService resultParserService;
    private final RunningExecutionRegistry runningRegistry;
    private final com.bigdata.scriptbox.service.ArtifactService artifactService;

    /**
     * 同步执行一次脚本。
     *
     * @param req 脚本执行请求
     * @return 执行完成后的 ExecutionHistory 行
     */
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

    /**
     * 预演执行：返回命令、环境（脱敏）、参数，不实际启动进程。
     *
     * @param req 脚本执行请求
     * @return 预演快照
     */
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

    /** V2: 列出当前匹配 (script, tenant) 的运行中执行。
     *  用于前端在「取消正在等待的那条」时查找 executionId。
     *  因为 POST /executions 是阻塞到结束的，前端拿不到 id。 */
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

    /** V2: 取消正在运行的执行。幂等 — 已结束的返回 "not found"。
     *  取消在后台线程完成；执行线程返回后 history 行会更新为 CANCELLED。 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id) {
        ExecutionHistory existing = executor.history(id);
        if (existing == null) {
            // 可能仍在跑且还没写库，先到 registry 里找
            RunningExecutionRegistry.RunningExecution live = runningRegistry.get(id);
            if (live == null) return ApiResponse.error("execution not found");
            boolean signalled = runningRegistry.cancel(id);
            log.info("用户取消脚本执行，executionId={}，signalled={}", id, signalled);
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("id", id);
            data.put("cancelled", signalled);
            data.put("state", "RUNNING");
            return ApiResponse.ok(data);
        }
        // 已结束，不能再取消
        if (!"RUNNING".equals(existing.getStatus())) {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("id", id);
            data.put("cancelled", false);
            data.put("state", existing.getStatus());
            data.put("message", "execution already finished with status " + existing.getStatus());
            return ApiResponse.ok(data);
        }
        boolean signalled = runningRegistry.cancel(id);
        log.info("用户取消脚本执行，executionId={}，signalled={}", id, signalled);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", id);
        data.put("cancelled", signalled);
        data.put("state", "RUNNING");
        return ApiResponse.ok(data);
    }

    /** V2: 查询某次执行的当前状态。 */
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

    /**
     * 读取执行 stdout 日志（截断到 {@code maxLogBytes}）。
     *
     * @param id 执行 ID
     * @return stdout 内容
     */
    @GetMapping(value = "/{id}/stdout", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> stdout(@PathVariable Long id) throws IOException {
        ExecutionHistory h = executor.history(id);
        if (h == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(executor.readStdout(h));
    }

    /** V2: 按快照重放执行。快照中敏感变量已脱敏，DANGEROUS 脚本不再二次确认。 */
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

    /**
     * 读取执行 stderr 日志（截断到 {@code maxLogBytes}）。
     *
     * @param id 执行 ID
     * @return stderr 内容
     */
    @GetMapping(value = "/{id}/stderr", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> stderr(@PathVariable Long id) throws IOException {
        ExecutionHistory h = executor.history(id);
        if (h == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(executor.readStderr(h));
    }

    /**
     * 读取 result.json 解析后的内容。
     *
     * <p>缺失 result.json 视为正常状态（脚本没写），返回 code=0 且 data=null，
     * 让前端渲染「未生成 result.json」占位。
     *
     * @param id 执行 ID
     * @return 解析后的 Map
     */
    @GetMapping(value = "/{id}/result")
    public ApiResponse<Map<String, Object>> result(@PathVariable Long id) {
        ExecutionHistory h = executor.history(id);
        if (h == null) return ApiResponse.error("history not found");
        Map<String, Object> data = resultParserService.readStructured(h);
        // V2: missing result.json is a normal "script didn't write one" state,
        // not an error. Return code=0 with data=null so the front-end renders
        // its "未生成 result.json" placeholder instead of an error toast. Real
        // failures (file system errors, parse errors) still surface as exceptions.
        return ApiResponse.ok(data);
    }

    /**
     * V2: 列出一次执行的所有产物（$ARTIFACT_DIR 下的文件）。
     * 没有产物时返回空列表。
     *
     * @param id 执行 ID
     * @return 产物列表
     */
    @GetMapping("/{id}/artifacts")
    public ApiResponse<java.util.List<Map<String, Object>>> artifacts(@PathVariable Long id) {
        ExecutionHistory h = executor.history(id);
        if (h == null) return ApiResponse.error("history not found");
        return ApiResponse.ok(artifactService.listView(id));
    }

    /**
     * V2: 下载单个产物。
     *
     * <p>{@code name} 接受产物主键或相对名（如 {@code report.csv} 或
     * {@code artifacts/report.csv}）。所有文件系统路径都走
     * {@link com.bigdata.scriptbox.service.ArtifactService#resolveSafe}，
     * 拒绝穿越目录与绝对路径。
     *
     * @param id 执行 ID
     * @param name 产物标识
     * @return 文件内容
     */
    @GetMapping("/{id}/artifacts/{name}")
    public ResponseEntity<byte[]> downloadArtifact(@PathVariable Long id,
                                                   @PathVariable String name) throws IOException {
        ExecutionHistory h = executor.history(id);
        if (h == null) return ResponseEntity.notFound().build();
        var resolved = artifactService.resolveSafe(id, name);
        if (resolved == null) return ResponseEntity.notFound().build();
        byte[] body = java.nio.file.Files.readAllBytes(resolved.onDisk);
        return ResponseEntity.ok()
                .contentType(resolved.meta.getMimeType() != null
                        ? org.springframework.http.MediaType.parseMediaType(resolved.meta.getMimeType())
                        : org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition",
                        "attachment; filename=\"" + safeFilename(resolved.meta.getName()) + "\"")
                .body(body);
    }

    /** 剥离目录部分与控制字符，避免 Content-Disposition 被注入 CRLF 或引号破坏响应头。 */
    private String safeFilename(String name) {
        if (name == null || name.isBlank()) return "artifact";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        // 过滤掉会破坏响应头的控制字符与引号
        return base.replaceAll("[\\r\\n\\\"\\\\]", "_");
    }
}