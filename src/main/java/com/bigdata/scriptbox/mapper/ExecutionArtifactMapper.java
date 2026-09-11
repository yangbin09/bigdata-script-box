package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.ExecutionArtifact;
import org.apache.ibatis.annotations.Mapper;

/**
 * 脚本执行产物表 Mapper。
 *
 * <p>对应表 {@code execution_artifact}。每次脚本运行结束后由
 * {@link com.bigdata.scriptbox.service.ArtifactService} 扫描执行目录并
 * 落条目；前端通过关联查询获取产物列表。
 */
@Mapper
public interface ExecutionArtifactMapper extends BaseMapper<ExecutionArtifact> {
}