package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.Script;
import org.apache.ibatis.annotations.Mapper;

/**
 * 脚本表 Mapper。
 *
 * <p>对应表 {@code script}。脚本本体的文件名（{@code scriptPath}）落在
 * 受控目录，DB 仅存元数据。
 */
@Mapper
public interface ScriptMapper extends BaseMapper<Script> {
}