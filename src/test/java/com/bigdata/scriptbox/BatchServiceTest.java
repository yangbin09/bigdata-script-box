package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.BatchService;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchServiceTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private BatchService batchService;

    private Long seedScript() throws Exception {
        Script s = new Script();
        s.setName("batch-script-" + System.nanoTime());
        s.setDisplayName("Batch Script");
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", "b.sh", "application/x-sh",
                "#!/bin/bash\necho batch\n".getBytes(StandardCharsets.UTF_8)));
        ScriptParam p = new ScriptParam();
        p.setName("greeting"); p.setType("text");
        scriptService.replaceParams(saved.getId(), List.of(p));
        return saved.getId();
    }

    private Long seedTenant() {
        Tenant t = new Tenant();
        t.setName("batch-tenant-" + System.nanoTime());
        t.setPrincipal("batch@EXAMPLE.COM");
        t.setEnabled(true);
        return tenantService.create(t).getId();
    }

    @Test
    void runsAllRowsAndAttachesBatchId() throws Exception {
        Long sid = seedScript();
        Long tid = seedTenant();
        BatchService.BatchSummary sum = batchService.runSequential(sid, tid, null,
                List.of(
                        Map.of("greeting", "hello"),
                        Map.of("greeting", "world"),
                        Map.of("greeting", "again")));
        assertEquals(3, sum.total());
        assertEquals(3, sum.historyIds().size());
        assertEquals(3, sum.succeeded());
        assertEquals(0, sum.failed());
        assertNotNull(sum.batchId());

        List<ExecutionHistory> rows = batchService.findByBatch(sum.batchId());
        assertEquals(3, rows.size());
        for (ExecutionHistory h : rows) {
            assertEquals(sum.batchId(), h.getBatchId());
            assertTrue(h.getSuccess());
            assertEquals("SUCCESS", h.getStatus());
        }
        // batch_row_index should be present
        boolean idxPresent = rows.stream().anyMatch(h -> h.getBatchRowIndex() != null);
        assertTrue(idxPresent);
    }

    @Test
    void rejectsEmptyRows() {
        assertThrows(IllegalArgumentException.class,
                () -> batchService.runSequential(1L, 1L, null, List.of()));
    }

    @Test
    void summaryRowsHaveFriendlyShape() throws Exception {
        Long sid = seedScript();
        Long tid = seedTenant();
        BatchService.BatchSummary sum = batchService.runSequential(sid, tid, null,
                List.of(Map.of("greeting", "a")));
        var rows = batchService.summarize(batchService.findByBatch(sum.batchId()));
        assertEquals(1, rows.size());
        var row = rows.get(0);
        assertTrue(row.containsKey("scriptName"));
        assertTrue(row.containsKey("status"));
        assertTrue(row.containsKey("success"));
    }

    @Test
    void emptyBatchLookupReturnsEmpty() {
        var rows = batchService.findByBatch("nonexistent-batch-id-12345");
        assertTrue(rows.isEmpty());
    }
}