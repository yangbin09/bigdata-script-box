package com.bigdata.scriptbox.dto;

import java.util.List;
import java.util.Map;

/**
 * 脚本执行的"预览"视图（V3 / #8）。
 *
 * <p>由 {@code ScriptExecutor.preview()} 返回，对应 dry-run 模式下前端想看的：
 * <ul>
 *   <li>解析后的脚本元数据（id / 名称 / 路径 / 超时 / 启用状态）；</li>
 *   <li>生效的租户信息（id / 名称 / principal）；</li>
 *   <li>合并并校验后的入参（{@code params}）；</li>
 *   <li>实际要启动的 {@code command} 列表（已是 List<String>，不含 shell 注入风险）；</li>
 *   <li>是否会被 kinit wrapper 包裹（kinitWrapped）；</li>
 *   <li>遮罩后的环境变量（globalVariables，敏感值已 mask）；</li>
 *   <li>keytab 配置状态（keytabSet）+ 遮罩后的路径（keytabPath，{@code "***"} 或 null）。</li>
 * </ul>
 *
 * <p>字段命名与原 {@code Map<String,Object>} 的键名逐一对应（前端按字符串键消费），
 * 保持零前端改动。前端仅需把对 {@code response.data} 的访问改成
 * {@code response.data.scriptId} 等字段访问（Jackson 已支持 record）。
 *
 * @param scriptId          脚本 ID
 * @param scriptName        脚本名（DB 主键名）
 * @param scriptDisplayName 脚本展示名
 * @param scriptPath        脚本绝对路径
 * @param tenantId          租户 ID
 * @param tenantName        租户名
 * @param principal         租户 principal
 * @param timeoutSeconds    脚本执行超时（秒；null 表示用默认）
 * @param enabled           脚本启用状态
 * @param params            合并 + 类型校验后的入参（key → value）
 * @param command           要启动的命令行（List<String>，已不含用户输入拼接）
 * @param kinitWrapped      是否会被 kinit wrapper 包裹（true 时命令指向临时 wrapper 文件）
 * @param globalVariables   遮罩后的环境变量（敏感值已替换为 {@code "***"}）
 * @param keytabSet         租户是否配置了 keytab
 * @param keytabPath        遮罩后的 keytab 路径（{@code "***"} 或 null）
 */
public record ExecutionPreview(
        Long scriptId,
        String scriptName,
        String scriptDisplayName,
        String scriptPath,
        Long tenantId,
        String tenantName,
        String principal,
        Integer timeoutSeconds,
        Boolean enabled,
        Map<String, String> params,
        List<String> command,
        boolean kinitWrapped,
        Map<String, String> globalVariables,
        boolean keytabSet,
        String keytabPath
) {}