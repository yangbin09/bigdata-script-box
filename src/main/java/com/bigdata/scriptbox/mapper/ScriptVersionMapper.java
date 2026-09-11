package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.ScriptVersion;

import java.util.List;

public interface ScriptVersionMapper extends BaseMapper<ScriptVersion> {
    List<ScriptVersion> selectByScriptId(Long scriptId);
    ScriptVersion selectMaxVersion(Long scriptId);
}