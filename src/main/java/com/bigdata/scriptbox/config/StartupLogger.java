package com.bigdata.scriptbox.config;

import com.bigdata.scriptbox.service.StoragePathService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 启动就绪日志。
 *
 * <p>Spring Boot {@code ApplicationReadyEvent} 是「应用完全就绪、HTTP 监听器已开启」
 * 的事件。这里打一条 INFO 日志，列出关键配置（路径、并发上限、超时等），
 * 让运维 / 开发一眼就能看到生效的值，避免「改了配置没生效」的常见问题。
 *
 * <p>为何不打 WARN：启动阶段不打错误；任何路径问题会在第一次 IO 时自然暴露。
 */
@Component
public class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final ScriptBoxProperties props;
    private final StoragePathService storagePathService;

    public StartupLogger(ScriptBoxProperties props, StoragePathService storagePathService) {
        this.props = props;
        this.storagePathService = storagePathService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        log.info("应用已就绪：mock={} max-concurrent={} retention(history={}d, artifact={}d, execution={}d, log={}d)",
                props.isMock(),
                props.getMaxConcurrent(),
                props.getRetentionHistoryDays(),
                props.getRetentionArtifactDays(),
                props.getRetentionExecutionDays(),
                props.getRetentionLogDays());
        log.info("数据路径：data={}", safePath(storagePathService::dataRoot));
        log.info("脚本路径：scripts={}", safePath(storagePathService::scriptsRoot));
        log.info("执行路径：executions={}", safePath(storagePathService::executionsRoot));
        log.info("日志路径：logs={}", safePath(storagePathService::logsRoot));
    }

    @FunctionalInterface
    private interface PathSupplier {
        Path get();
    }

    private static String safePath(PathSupplier p) {
        try { return String.valueOf(p.get()); }
        catch (Exception e) { return "<unavailable: " + e.getMessage() + ">"; }
    }
}