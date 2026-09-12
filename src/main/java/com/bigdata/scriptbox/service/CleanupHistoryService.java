package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.CleanupHistory;
import com.bigdata.scriptbox.mapper.CleanupHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V2: 围绕 {@code cleanup_history} 表的极简审计服务。
 *
 * <p>{@link CleanupService} 每次执行清理都会写入一行，使设置页面可以展示
 * "最近的清理"列表，同时给运维留痕。
 *
 * <p>没有缓存层：该表一周最多写几行，读取按需执行；直接读库避免缓存与设置页面
 * 不一致的迷惑。
 */
@Service
@RequiredArgsConstructor
public class CleanupHistoryService {

    private final CleanupHistoryMapper mapper;

    /**
     * 写入一条审计记录。{@code createdAt} 为空时填入当前时间。
     */
    public CleanupHistory record(CleanupHistory row) {
        if (row == null) return null;
        if (row.getCreatedAt() == null) row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
        return row;
    }

    /**
     * 读取最近 N 条审计记录（按 {@code created_at} 倒序）。limit 被夹在 [1, 200]。
     */
    public List<CleanupHistory> listRecent(int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        return mapper.selectList(new QueryWrapper<CleanupHistory>()
                .orderByDesc("created_at").last("LIMIT " + n));
    }
}