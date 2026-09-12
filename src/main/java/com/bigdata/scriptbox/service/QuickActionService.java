package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.QuickAction;
import com.bigdata.scriptbox.mapper.QuickActionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V3 (PR-5): 快捷操作 CRUD。
 *
 * <p>列表按 sortOrder asc, id asc 排序（创建时 sort_order = MAX+1，落在末尾）。
 * 删除是直接 deleteById；编辑是 updateById。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QuickActionService {

    private final QuickActionMapper quickActionMapper;

    public List<QuickAction> listAll() {
        return quickActionMapper.selectList(
                new QueryWrapper<QuickAction>().orderByAsc("sort_order", "id"));
    }

    public QuickAction getById(Long id) {
        return quickActionMapper.selectById(id);
    }

    public QuickAction create(QuickAction qa) {
        if (qa.getIcon() == null) qa.setIcon("⚡");
        if (qa.getSortOrder() == null) {
            Integer max = quickActionMapper.selectCount(new QueryWrapper<>()) > 0
                    ? quickActionMapper.selectList(new QueryWrapper<QuickAction>()
                            .orderByDesc("sort_order").last("LIMIT 1"))
                            .stream().findFirst().map(QuickAction::getSortOrder).orElse(0)
                    : 0;
            qa.setSortOrder(max + 1);
        }
        LocalDateTime now = LocalDateTime.now();
        qa.setCreateTime(now);
        qa.setUpdateTime(now);
        quickActionMapper.insert(qa);
        log.info("新增快捷操作，qaId={}，name={}", qa.getId(), qa.getName());
        return qa;
    }

    public QuickAction update(QuickAction qa) {
        qa.setUpdateTime(LocalDateTime.now());
        quickActionMapper.updateById(qa);
        return quickActionMapper.selectById(qa.getId());
    }

    public void delete(Long id) {
        quickActionMapper.deleteById(id);
        log.info("删除快捷操作，qaId={}", id);
    }

    /**
     * 重排：传入 ID 列表，按列表顺序重写 sort_order。
     */
    public void reorder(List<Long> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            QuickAction qa = new QuickAction();
            qa.setId(orderedIds.get(i));
            qa.setSortOrder(i);
            qa.setUpdateTime(LocalDateTime.now());
            quickActionMapper.updateById(qa);
        }
    }
}