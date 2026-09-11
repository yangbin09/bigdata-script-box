package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.SystemSetting;
import com.bigdata.scriptbox.mapper.SystemSettingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V2: persisted runtime settings. Stored in the {@code system_setting}
 * table; read through a typed {@code getInt}/{@code getLong} facade that
 * falls back to the supplied default when the row is missing or the value
 * doesn't parse. Writes upsert by primary key.
 *
 * <p>This is intentionally simple — no caching layer. The cleanup
 * scheduler runs at most once per day, so a direct DB read per
 * invocation is fine and avoids stale-cache confusion.
 */
@Service
public class SystemSettingService {

    @Autowired private SystemSettingMapper mapper;

    public Integer getInt(String key, Integer defaultValue) {
        String v = getRaw(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException nfe) { return defaultValue; }
    }

    public Long getLong(String key, Long defaultValue) {
        String v = getRaw(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException nfe) { return defaultValue; }
    }

    public String getString(String key, String defaultValue) {
        String v = getRaw(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    public Boolean getBoolean(String key, Boolean defaultValue) {
        String v = getRaw(key);
        if (v == null || v.isBlank()) return defaultValue;
        return Boolean.parseBoolean(v.trim());
    }

    public String getRaw(String key) {
        SystemSetting s = mapper.selectById(key);
        return s == null ? null : s.getSettingValue();
    }

    public List<SystemSetting> listAll() {
        return mapper.selectList(new QueryWrapper<SystemSetting>().orderByAsc("setting_key"));
    }

    public SystemSetting upsert(String key, String value, String description) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("setting_key is required");
        SystemSetting existing = mapper.selectById(key);
        SystemSetting s = existing == null ? new SystemSetting() : existing;
        s.setSettingKey(key);
        s.setSettingValue(value);
        s.setDescription(description);
        s.setUpdateTime(LocalDateTime.now());
        if (existing == null) mapper.insert(s);
        else mapper.updateById(s);
        return s;
    }
}