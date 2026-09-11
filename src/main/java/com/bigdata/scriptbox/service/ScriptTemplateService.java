package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.ScriptTemplate;
import com.bigdata.scriptbox.mapper.ScriptTemplateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * V2: read-only access to the built-in script templates. Templates are
 * seeded by {@code DataInitializer} on first launch; users don't edit
 * them directly — instead they "create from template", which copies the
 * content into a real Script row.
 */
@Service
public class ScriptTemplateService {

    @Autowired private ScriptTemplateMapper mapper;

    public List<ScriptTemplate> listEnabled() {
        return mapper.selectList(
                new QueryWrapper<ScriptTemplate>()
                        .eq("enabled", true)
                        .orderByAsc("sort_order", "id"));
    }

    public List<ScriptTemplate> listAll() {
        return mapper.selectList(
                new QueryWrapper<ScriptTemplate>()
                        .orderByAsc("sort_order", "id"));
    }

    public ScriptTemplate findByCode(String code) {
        if (code == null) return null;
        return mapper.selectOne(
                new QueryWrapper<ScriptTemplate>().eq("code", code));
    }

    /** Upsert by {@code code} — used by the seeder and by future admin
     *  endpoints that want to add or update built-in templates. */
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