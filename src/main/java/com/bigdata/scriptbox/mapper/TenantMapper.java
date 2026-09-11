package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户表 Mapper。
 *
 * <p>对应表 {@code tenant}。keytab 文件本体落在受控目录，DB 仅存路径与
 * Kerberos principal。
 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
}