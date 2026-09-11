package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.ScriptPreset;

import java.util.List;

public interface ScriptPresetMapper extends BaseMapper<ScriptPreset> {
    List<ScriptPreset> selectByScriptId(Long scriptId);
}