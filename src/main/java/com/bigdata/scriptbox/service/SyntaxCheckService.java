package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.executor.CommandExecutor;
import com.bigdata.scriptbox.executor.CommandResult;
import com.bigdata.scriptbox.executor.CommandSpec;
import com.bigdata.scriptbox.util.TextDecoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简易脚本语法静态校验：在保存 / 预览脚本前对内容做基础检查，拦截明显错误的脚本。
 *
 * <p>首选实现：把内容写到临时文件后跑 {@code bash -n}（即"只解析不执行"）。
 * bash 会输出形如 {@code line 2: syntax error: ...} 的诊断信息，这些信息会被原样
 * 收入 {@code errors} 列表，与真实 bash 行为完全一致。
 *
 * <p>子进程统一走 {@link CommandExecutor}（历史上本类自己 {@code new ProcessBuilder}
 * 且 {@code waitFor()} 无超时，会让请求线程永久挂起）。shell 可执行文件由
 * {@code scriptbox.shell-executable} 配置，因为部署目标是 Linux，而本地开发环境
 * （Windows + WSL 的 {@code C:\Windows\system32\bash.exe}）会把 {@code C:\Users\...}
 * 吞成 {@code C:Users...}，任何带路径参数的调用都会失败。
 *
 * <p>fallback：当 shell 不可用（精简容器 / 非 POSIX 平台 / 启动失败）时退回到
 * 字符级启发式检查（行长度 / 引号平衡 / here-doc 闭合 / 关键字配对），尽力而为。
 *
 * <p>注意：{@code bash -n} 不会执行 set -e 之类的副作用；它只解析语法。这是
 * "软"检查，目的是拦截明显笔误，不要也不可能替代真正的 lint。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SyntaxCheckService {

    private final ScriptBoxProperties props;
    private final CommandExecutor commandExecutor;

    /**
     * 语法校验结果。{@link #ok} = true 表示没有 error；warning 不影响 ok。
     */
    public static class SyntaxResult {
        /** 是否通过。 */
        public final boolean ok;
        /** 错误条目（每条对应一行 / 一类问题）。 */
        public final List<String> errors;
        /** 警告条目，不影响 ok 但建议用户看一眼。 */
        public final List<String> warnings;

        public SyntaxResult(boolean ok, List<String> errors, List<String> warnings) {
            this.ok = ok;
            this.errors = errors;
            this.warnings = warnings;
        }

        /**
         * 序列化为 Map 供前端 JSON 序列化使用。
         */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", ok);
            m.put("errors", errors);
            m.put("warnings", warnings);
            return m;
        }
    }

    /**
     * 对脚本内容做静态校验。任何异常都不会抛 —— 校验失败会被收成 errors 返回。
     */
    public SyntaxResult check(String content) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        // 空内容视为合法 —— 占位脚本允许先建空壳，再补内容
        if (content == null || content.isBlank()) {
            return new SyntaxResult(true, errors, warnings);
        }

        // 尝试用 bash -n；这是最权威的检查
        BashResult bash = tryBashN(content);
        if (bash != null) {
            if (bash.exitCode != 0) {
                errors.addAll(bash.diagnostics);
                if (errors.isEmpty()) {
                    errors.add("bash -n 退出码 " + bash.exitCode);
                }
            } else if (!bash.diagnostics.isEmpty()) {
                // bash 退出 0 也可能产生 stderr warning（如 deprecation 提示），归到 warnings
                warnings.addAll(bash.diagnostics);
            }
            return new SyntaxResult(errors.isEmpty(), errors, warnings);
        }

        // fallback：启发式检查
        heuristicCheck(content, errors, warnings);
        return new SyntaxResult(errors.isEmpty(), errors, warnings);
    }

    /**
     * 跑 {@code bash -n}。把内容写到临时文件，通过 {@link CommandExecutor} 执行并
     * 捕获合并输出（bash 的诊断信息在 stderr 上，必须读回来才能报给用户）。
     *
     * <p>返回 null 表示 shell 不可用（启动失败 / 不在 PATH），由调用方退回启发式检查。
     *
     * <p>与旧实现的差别：
     * <ul>
     *   <li>旧版 {@code p.waitFor()} <b>无超时</b>，可挂死请求线程；现在由
     *       {@link CommandExecutor} 统一施加硬超时并回收进程树；</li>
     *   <li>不占用并发执行槽位（语法检查不是用户脚本）；</li>
     *   <li>诊断文本用 {@link TextDecoder} 宽容解码 —— Windows 上 bash 会写 UTF-16LE，
     *       严格解码会直接抛异常。</li>
     * </ul>
     */
    private BashResult tryBashN(String content) {
        Path tmp = null;
        Path outPath = null;
        Path errPath = null;
        try {
            tmp = Files.createTempFile("scriptbox-syntax-", ".sh");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            outPath = Files.createTempFile("scriptbox-syntax-out-", ".log");
            errPath = Files.createTempFile("scriptbox-syntax-err-", ".log");

            CommandSpec spec = new CommandSpec(
                    List.of(props.getShellExecutable(), "-n", tmp.toAbsolutePath().toString()),
                    null, Map.of(), outPath, errPath,
                    props.getCommandTimeoutSeconds(), "syntax-check", 0L, false);
            CommandResult result = commandExecutor.exec(spec);

            BashResult br = new BashResult();
            br.exitCode = result.exitCode();
            String combined = TextDecoder.readLenient(outPath)
                    + TextDecoder.readLenient(errPath);
            br.diagnostics = splitDiagnostics(combined);
            return br;
        } catch (IOException ex) {
            // shell 不存在 / 不可执行 → 交给启发式检查兜底
            log.debug("bash -n 不可用（{}），回退启发式语法检查", ex.getMessage());
            return null;
        } finally {
            deleteQuietly(tmp);
            deleteQuietly(outPath);
            deleteQuietly(errPath);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort；临时目录由操作系统回收
        }
    }

    /**
     * 把 bash 输出的多行诊断拆成 List。空行自动跳过。
     */
    private List<String> splitDiagnostics(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    /**
     * 字符级启发式检查 —— 仅在 bash 不可用时兜底。
     */
    private void heuristicCheck(String content, List<String> errors, List<String> warnings) {
        String[] lines = content.split("\\r?\\n", -1);
        boolean inHereDoc = false;
        String hereDocTag = null;
        int hereDocStartLine = -1;
        int unclosedSingle = 0;
        int unclosedDouble = 0;
        int ifCount = 0, forCount = 0, whileCount = 0, caseCount = 0;
        int fiCount = 0, doneCount = 0, esacCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = stripComment(raw);

            if (line.length() > 4096) {
                errors.add("第 " + (i + 1) + " 行超过 4096 字符");
            }

            if (inHereDoc) {
                if (hereDocTag != null && line.trim().equals(hereDocTag)) {
                    inHereDoc = false;
                    hereDocTag = null;
                }
                continue;
            }

            // 进入 here-doc
            if (line.matches(".*<<-?\\s*['\"]?\\w+['\"]?.*")) {
                String after = line.substring(line.indexOf("<<") + 2).replaceFirst("-\\s*", "").trim();
                String tag = after.split("\\s+", 2)[0].replaceAll("^['\"]|['\"]$", "");
                if (!tag.isEmpty() && Character.isLetter(tag.charAt(0))) {
                    inHereDoc = true;
                    hereDocTag = tag;
                    hereDocStartLine = i + 1;
                    continue;
                }
            }

            // 行内引号平衡（不考虑转义，粗糙统计）
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '\'') unclosedSingle ^= 1;
                else if (c == '"') unclosedDouble ^= 1;
            }

            String trimmed = line.trim();
            if (trimmed.startsWith("if ") || trimmed.equals("if") || trimmed.startsWith("if[")) ifCount++;
            if (trimmed.startsWith("for ") || trimmed.equals("for") || trimmed.startsWith("for(")) forCount++;
            if (trimmed.startsWith("while ") || trimmed.equals("while")) whileCount++;
            if (trimmed.startsWith("case ") || trimmed.equals("case")) caseCount++;
            if (trimmed.equals("fi") || trimmed.startsWith("fi ")) fiCount++;
            if (trimmed.equals("done") || trimmed.startsWith("done ")) doneCount++;
            if (trimmed.equals("esac") || trimmed.startsWith("esac ")) esacCount++;
        }

        if (inHereDoc) {
            errors.add("here-doc 起始于第 " + hereDocStartLine + " 行，但未找到对应的结束标记");
        }
        if (unclosedSingle != 0) errors.add("line syntax error: 未闭合的单引号 '");
        if (unclosedDouble != 0) errors.add("line syntax error: 未闭合的双引号 \"");

        if (ifCount != fiCount) {
            errors.add("line syntax error: if/fi 不匹配 (if=" + ifCount + ", fi=" + fiCount + ")");
        }
        if (forCount + whileCount != doneCount) {
            errors.add("line syntax error: for/while 与 done 不匹配");
        }
        if (caseCount != esacCount) {
            errors.add("line syntax error: case/esac 不匹配");
        }
    }

    /** 去掉行末注释（保留行内注释视为正文，不展开解析）。 */
    private String stripComment(String line) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '#' && !inSingle && !inDouble) return line.substring(0, i);
        }
        return line;
    }

    /** bash -n 调用的结构化结果。 */
    private static class BashResult {
        int exitCode;
        List<String> diagnostics = new ArrayList<>();
    }
}