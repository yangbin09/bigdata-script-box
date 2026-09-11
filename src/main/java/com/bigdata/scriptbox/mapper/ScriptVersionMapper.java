package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.ScriptVersion;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 脚本版本表 Mapper。
 *
 * <p>对应表 {@code script_version}。每次保存脚本正文都会追加一行；
 * 回滚操作也走"复制旧版本再保存"的语义，所以本表是 append-only 的。
 */
@Mapper
public interface ScriptVersionMapper extends BaseMapper<ScriptVersion> {
    /**
     * 查询某脚本的全部版本，按 version_no 降序。
     *
     * @param scriptId 脚本 ID
     * @return 版本列表
     */
    List<ScriptVersion> selectByScriptId(Long scriptId);

    /**
     * 查询某脚本当前最大的版本号（用于保存时生成下一个版本号）。
     *
     * @param scriptId 脚本 ID
     * @return 最新版本对象（可能为空）
     */
    ScriptVersion selectMaxVersion(Long scriptId);
}