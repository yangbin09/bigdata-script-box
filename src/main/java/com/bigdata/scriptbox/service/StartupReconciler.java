package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.model.ExecutionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * V3 (PR-0): 应用启动时对账 —— 把 DB 里残留的 RUNNING / PENDING 行扫成 INTERRUPTED。
 *
 * <p><b>为什么必须做</b>：服务重启时 JVM 内的 ExecutionGate 状态被清空，但 H2 里
 * 上次进程没来得及收尾的 RUNNING 行还在原位。如果不处理，前端历史页会一直看到
 * 这些行挂着 "RUNNING"，但实际上对应的进程已经死了（kill -9 或者 OOM）。
 *
 * <p>清理策略：把这些行的 status 改为 INTERRUPTED，写入 end_time 与
 * {@code interrupted_reason='process_restart'}。业务上的"我主动取消"不会被影响
 * —— 那些行是 CANCELLED 状态。
 *
 * <p>注册时机：{@link ApplicationReadyEvent}，此时 Tomcat 已就绪、连接池已建立，
 * 但还没有用户请求进来。同步阻塞对启动时间几乎无影响（通常几十行）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StartupReconciler {

    private final ExecutionHistoryMapper historyMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        try {
            List<ExecutionHistory> active = historyMapper.selectActive();
            if (active == null || active.isEmpty()) {
                log.info("StartupReconciler: 无残留活跃执行");
                return;
            }
            List<Long> ids = active.stream()
                    .map(ExecutionHistory::getId)
                    .collect(Collectors.toList());
            LocalDateTime now = LocalDateTime.now();
            int updated = historyMapper.markInterrupted(ids, "process_restart", now);
            log.warn("StartupReconciler: 扫表发现 {} 条残留活跃执行，已改写为 INTERRUPTED（原因：process_restart）",
                    updated);
            // 单独记一条 info 列出 ID，方便运维对照日志。
            for (ExecutionHistory h : active) {
                log.info("StartupReconciler: 残留行 executionId={} scriptId={} tenantId={} 原 status={}",
                        h.getId(), h.getScriptId(), h.getTenantId(), h.getStatus());
            }
        } catch (Exception ex) {
            // 启动期对账失败不应阻塞应用启动 —— 但要明显地告警。
            log.error("StartupReconciler: 扫表失败（不阻塞启动）：{}", ex.getMessage(), ex);
        }
    }
}
