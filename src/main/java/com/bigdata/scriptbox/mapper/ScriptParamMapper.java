package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.ScriptParam;

import java.util.List;

public interface ScriptParamMapper extends BaseMapper<ScriptParam> {
    List<ScriptParam> selectByScriptId(Long scriptId);
}