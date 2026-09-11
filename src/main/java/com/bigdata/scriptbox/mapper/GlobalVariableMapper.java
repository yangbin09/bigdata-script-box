package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.GlobalVariable;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 全局变量表 Mapper。
 *
 * <p>对应表 {@code global_variable}。{@link #selectEnabled()} 是脚本执行
 * 路径上的热路径：每次执行前由 ScriptExecutor 拉取全部启用项注入到
 * {@link ProcessBuilder} 环境里。
 */
@Mapper
public interface GlobalVariableMapper extends BaseMapper<GlobalVariable> {
    /**
     * 查询所有启用的全局变量（用于注入执行环境）。
     *
     * @return 启用项列表
     */
    List<GlobalVariable> selectEnabled();
}