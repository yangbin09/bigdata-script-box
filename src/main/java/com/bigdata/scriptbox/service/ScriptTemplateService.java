package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.ScriptTemplate;
import com.bigdata.scriptbox.mapper.ScriptTemplateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * V2: 脚本模板只读访问。
 *
 * <p>模板由 {@code DataInitializer} 首次启动时按 {@code script_template.yml}
 * 种子数据写入；用户不直接编辑模板，而是通过"从模板创建"接口把模板内容复制
 * 成真实 Script 行。
 */
@Service
public class ScriptTemplateService {

    @Autowired private ScriptTemplateMapper mapper;

    /** 列出所有启用的模板（前端展示用）。 */
    public List<ScriptTemplate> listEnabled() {
        return mapper.selectList(
                new QueryWrapper<ScriptTemplate>()
                        .eq("enabled", true)
                        .orderByAsc("sort_order", "id"));
    }

    /** 列出全部模板（含禁用），供管理页调试使用。 */
    public List<ScriptTemplate> listAll() {
        return mapper.selectList(
                new QueryWrapper<ScriptTemplate>()
                        .orderByAsc("sort_order", "id"));
    }

    /**
     * 按 {@code code} 查模板。{@code code} 是稳定标识，API 路径里用。
     */
    public ScriptTemplate findByCode(String code) {
        if (code == null) return null;
        return mapper.selectOne(
                new QueryWrapper<ScriptTemplate>().eq("code", code));
    }

    /**
     * 按 {@code code} upsert：seed 和未来管理端都走这里。
     */
    public ScriptTemplate createOrUpdate(ScriptTemplate t) {
        ScriptTemplate existing = findByCode(t.getCode());
        if (existing == null) {
            mapper.insert(t);
            return t;
        }
        t.setId(existing.getId());
        mapper.updateById(t);
        return t;
    }
}