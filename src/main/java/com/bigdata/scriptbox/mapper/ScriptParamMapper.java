package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.ScriptParam;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 脚本参数表 Mapper。
 *
 * <p>对应表 {@code script_param}。{@link #selectByScriptId(Long)} 在执行
 * 路径上读取参数定义（用于默认值兜底与类型校验）。
 */
@Mapper
public interface ScriptParamMapper extends BaseMapper<ScriptParam> {
    /**
     * 按脚本 ID 查询全部参数定义，按 sort_order 升序。
     *
     * @param scriptId 脚本 ID
     * @return 参数列表
     */
    List<ScriptParam> selectByScriptId(Long scriptId);
}