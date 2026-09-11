package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HistoryService {

    @Autowired
    private ExecutionHistoryMapper historyMapper;

    public List<ExecutionHistory> listRecent(int limit) {
        return historyMapper.selectList(
                new QueryWrapper<ExecutionHistory>()
                        .orderByDesc("id")
                        .last("LIMIT " + Math.max(1, Math.min(limit, 500))));
    }

    public ExecutionHistory getById(Long id) {
        return historyMapper.selectById(id);
    }
}