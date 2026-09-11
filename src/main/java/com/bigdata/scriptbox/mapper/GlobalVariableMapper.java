package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.GlobalVariable;

import java.util.List;

public interface GlobalVariableMapper extends BaseMapper<GlobalVariable> {
    List<GlobalVariable> selectEnabled();
}