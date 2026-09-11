package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.SystemSetting;
import com.bigdata.scriptbox.mapper.SystemSettingMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V2: 持久化的运行时设置。存在 {@code system_setting} 表；通过类型化的
 * {@code getInt} / {@code getLong} / {@code getString} / {@code getBoolean} 接口读取，
 * 缺失或解析失败时回退到调用方提供的默认值。写入按主键 upsert。
 *
 * <p>故意不做缓存层：清理调度每天最多执行一次，每次直读数据库完全没问题，还能
 * 避免"缓存里的旧值"与"刚刚改的设置"对不上的迷惑。
 */
@Service
public class SystemSettingService {

    private final SystemSettingMapper mapper;

    /** 构造器注入：依赖显式化，字段 final 不可变。 */
    public SystemSettingService(SystemSettingMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 按 key 读取整数。缺失或解析失败返回 defaultValue。
     */
    public Integer getInt(String key, Integer defaultValue) {
        String v = getRaw(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException nfe) { return defaultValue; }
    }

    /**
     * 按 key 读取长整数。缺失或解析失败返回 defaultValue。
     */
    public Long getLong(String key, Long defaultValue) {
        String v = getRaw(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException nfe) { return defaultValue; }
    }

    /**
     * 按 key 读取字符串。缺失或空串返回 defaultValue。
     */
    public String getString(String key, String defaultValue) {
        String v = getRaw(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    /**
     * 按 key 读取布尔值。缺失或空串返回 defaultValue。
     */
    public Boolean getBoolean(String key, Boolean defaultValue) {
        String v = getRaw(key);
        if (v == null || v.isBlank()) return defaultValue;
        return Boolean.parseBoolean(v.trim());
    }

    /**
     * 读原始字符串值（trim 由调用方决定）。
     */
    public String getRaw(String key) {
        SystemSetting s = mapper.selectById(key);
        return s == null ? null : s.getSettingValue();
    }

    /**
     * 列出全部设置（按 key 升序）。
     */
    public List<SystemSetting> listAll() {
        return mapper.selectList(new QueryWrapper<SystemSetting>().orderByAsc("setting_key"));
    }

    /**
     * 按 key upsert（更新或插入）。自动刷新 {@code update_time}。
     */
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