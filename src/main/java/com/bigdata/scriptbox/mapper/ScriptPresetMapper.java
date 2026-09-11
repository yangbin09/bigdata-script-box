package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.ScriptPreset;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 脚本预设表 Mapper。
 *
 * <p>对应表 {@code script_preset}。{@link #selectByScriptId(Long)} 在
 * 「预设」抽屉与「应用预设」流程里被调用。
 */
@Mapper
public interface ScriptPresetMapper extends BaseMapper<ScriptPreset> {
    /**
     * 查询某脚本的全部预设，按 ID 升序。
     *
     * @param scriptId 脚本 ID
     * @return 预设列表
     */
    List<ScriptPreset> selectByScriptId(Long scriptId);
}