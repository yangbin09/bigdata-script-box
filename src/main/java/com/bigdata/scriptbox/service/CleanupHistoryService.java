package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.CleanupHistory;
import com.bigdata.scriptbox.mapper.CleanupHistoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V2: tiny audit service around the {@code cleanup_history} table. The
 * CleanupService writes one row per execute call so the Settings UI can
 * show a 'recent cleanups' list and so the operator has a paper trail.
 *
 * <p>No caching — the table is only written a handful of times per week
 * at most, and reads happen on demand.
 */
@Service
public class CleanupHistoryService {

    @Autowired private CleanupHistoryMapper mapper;

    public CleanupHistory record(CleanupHistory row) {
        if (row == null) return null;
        if (row.getCreatedAt() == null) row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
        return row;
    }

    public List<CleanupHistory> listRecent(int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        return mapper.selectList(new QueryWrapper<CleanupHistory>()
                .orderByDesc("created_at").last("LIMIT " + n));
    }
}