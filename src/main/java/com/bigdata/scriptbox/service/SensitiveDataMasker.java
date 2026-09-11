package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.entity.GlobalVariable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感数据统一脱敏。
 *
 * <p>项目里至少有三个地方需要「把敏感值替换成 {@code ******}」：
 * <ol>
 *   <li>DryRun / Preview 时把敏感 GlobalVariable 的真实值遮掉。</li>
 *   <li>Snapshot 写入 execution_history 时同样要遮掉（snapshot 被持久化，
 *       不能让数据库里出现明文密码）。</li>
 *   <li>Process 启动命令 / 环境变量日志打印时遮掉 --token / --password 等参数。</li>
 * </ol>
 *
 * <p>把脱敏逻辑集中到这一个组件，避免每个调用方各自实现一遍。
 *
 * <p>实现约定：
 * <ul>
 *   <li>键（Keytab 路径、敏感 GlobalVariable 名称）不被遮；只遮值。</li>
 *   <li>空值 / null 不动。</li>
 *   <li>显式以全局变量记录的 {@code sensitive=true} 项才会被遮。</li>
 *   <li>命令行的脱敏按「白名单字段名」规则：参数名包含 password / token /
 *       secret / keytab-path / credential 的，后面那个值替换为 {@code ******}。</li>
 * </ul>
 */
@Component
public class SensitiveDataMasker {

    /** 命令行参数脱敏白名单（不区分大小写、子串匹配）。 */
    public static final Set<String> SENSITIVE_ARG_KEYWORDS = Set.of(
            "password", "passwd", "token", "secret", "credential", "keytab"
    );

    /** 全局遮罩字符串（与前端现有占位符一致）。 */
    public static final String MASK = "******";

    /** 匹配 {@code --xxx value} 的命令行片段，用于日志脱敏。 */
    private static final Pattern ARG_PAIR = Pattern.compile(
            "(--[A-Za-z][A-Za-z0-9_-]*)\\s+([^\\s]+)"
    );

    /**
     * 把环境变量 Map 中属于敏感 GlobalVariable 的值替换为 {@code ******}。
     * 不修改入参；返回一个新 Map。
     */
    public Map<String, String> maskEnv(Map<String, String> env, List<GlobalVariable> declared) {
        if (env == null || env.isEmpty()) return env == null ? Map.of() : env;
        Map<String, String> out = new LinkedHashMap<>(env);
        if (declared == null) return out;
        for (GlobalVariable v : declared) {
            if (Boolean.TRUE.equals(v.getSensitive())
                    && v.getVariableKey() != null
                    && out.containsKey(v.getVariableKey())) {
                out.put(v.getVariableKey(), MASK);
            }
        }
        return out;
    }

    /**
     * 脱敏命令行字符串（用于日志）。规则：
     * <ul>
     *   <li>形如 {@code --xxx value} 的相邻 token，如果 {@code xxx} 名称匹配
     *       敏感关键词（不区分大小写），把 {@code value} 替换为 {@code ******}。</li>
     *   <li>其余 token 保持原样。</li>
     * </ul>
     */
    public String maskCommand(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) return commandLine;
        Matcher m = ARG_PAIR.matcher(commandLine);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String arg = m.group(1);
            String value = m.group(2);
            String lower = arg.toLowerCase();
            boolean sensitive = false;
            for (String kw : SENSITIVE_ARG_KEYWORDS) {
                if (lower.contains(kw)) { sensitive = true; break; }
            }
            sb.append(commandLine, last, m.start());
            if (sensitive) {
                sb.append(arg).append(' ').append(MASK);
            } else {
                sb.append(arg).append(' ').append(value);
            }
            last = m.end();
        }
        if (last < commandLine.length()) sb.append(commandLine, last, commandLine.length());
        return sb.toString();
    }

    /**
     * 脱敏命令行 List（{@code ProcessBuilder.command()} 风格），返回新的 List。
     */
    public java.util.List<String> maskCommandList(java.util.List<String> command) {
        if (command == null || command.isEmpty()) return command;
        java.util.List<String> out = new java.util.ArrayList<>(command.size());
        int i = 0;
        while (i < command.size()) {
            String token = command.get(i);
            if (token != null && token.startsWith("--")
                    && i + 1 < command.size()
                    && !command.get(i + 1).startsWith("--")) {
                String lower = token.toLowerCase();
                boolean sensitive = false;
                for (String kw : SENSITIVE_ARG_KEYWORDS) {
                    if (lower.contains(kw)) { sensitive = true; break; }
                }
                out.add(token);
                out.add(sensitive ? MASK : command.get(i + 1));
                i += 2;
            } else {
                out.add(token);
                i++;
            }
        }
        return out;
    }
}
