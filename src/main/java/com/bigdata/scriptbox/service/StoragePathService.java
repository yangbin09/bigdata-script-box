package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.exception.BusinessErrorCode;
import com.bigdata.scriptbox.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 存储路径集中管理。
 *
 * <p>历史背景：项目里至少有 7 个类自己调用 {@code Paths.get(props.getExecutionsDir(), ...)}
 * 然后用 {@code normalize().startsWith()} 做安全校验。这些重复逻辑分散后
 * 容易出现两种 bug：
 * <ol>
 *   <li>不同地方用不同算法（有的用 {@code toAbsolutePath}，有的用 {@code toRealPath}），
 *       导致同一路径在不同调用点结论不一致。</li>
 *   <li>新增一类存储（如 input/）时容易忘记加校验。</li>
 * </ol>
 *
 * <p>本服务对外提供：
 * <ul>
 *   <li>绝对路径生成方法（{@link #scriptFilePath}、{@link #keytabPath}、
 *       {@link #executionDirFor}、{@link #artifactsDirFor}、{@link #inputDirFor}、
 *       {@link #pendingUploadDir}）；</li>
 *   <li>路径安全校验方法（{@link #assertInside}、{@link #isInsideReal}）；</li>
 *   <li>目录遍历辅助（{@link #safeList}）。</li>
 * </ul>
 *
 * <p>为什么不直接用 {@code FileSystems.getDefault().getPathMatcher()}：
 * 我们需要的是「路径必须在受控目录下」的硬约束，不是 glob 匹配。直接比较
 * 归一化后的路径前缀最简单可靠。
 */
@Service
public class StoragePathService {

    private static final Logger log = LoggerFactory.getLogger(StoragePathService.class);

    /** 执行目录名（每个 execution 一个子目录）。 */
    public static final String EXEC_DIR_PREFIX = "";
    /** 制品子目录名（脚本写到 {@code $ARTIFACT_DIR}）。 */
    public static final String ARTIFACTS_SUBDIR = "artifacts";
    /** 输入文件子目录（文件参数上传后拷贝到这里）。 */
    public static final String INPUT_SUBDIR = "input";
    /** 待上传文件根目录。 */
    public static final String PENDING_UPLOAD_SUBDIR = "uploads";

    private final ScriptBoxProperties props;

    public StoragePathService(ScriptBoxProperties props) {
        this.props = props;
    }

    // ----------------------------------------------------------------------
    // 路径生成
    // ----------------------------------------------------------------------

    /** 脚本目录的绝对路径（受控根）。 */
    public Path scriptsRoot() {
        return Paths.get(props.getScriptsDir()).toAbsolutePath().normalize();
    }

    /** keytab 目录的绝对路径（受控根）。 */
    public Path keytabsRoot() {
        return Paths.get(props.getKeytabsDir()).toAbsolutePath().normalize();
    }

    /** 执行根目录的绝对路径（受控根）。 */
    public Path executionsRoot() {
        return Paths.get(props.getExecutionsDir()).toAbsolutePath().normalize();
    }

    /** 应用日志目录的绝对路径（受控根，可不存在）。 */
    public Path logsRoot() {
        return Paths.get(props.getLogsDir() == null ? "./logs" : props.getLogsDir())
                .toAbsolutePath().normalize();
    }

    /** 数据根目录（受控根）。 */
    public Path dataRoot() {
        return Paths.get(props.getDataDir()).toAbsolutePath().normalize();
    }

    /** 单个脚本的磁盘路径：{@code <scriptsRoot>/<id>/script.sh}。 */
    public Path scriptFilePath(long scriptId) {
        return scriptsRoot().resolve(String.valueOf(scriptId)).resolve("script.sh");
    }

    /** keytab 实际路径：{@code <keytabsRoot>/tenant_<id>_<uuid>.keytab}。 */
    public Path keytabPath(long tenantId, String suffix) {
        return keytabsRoot().resolve("tenant_" + tenantId + "_" + suffix + ".keytab");
    }

    /** 执行目录：{@code <executionsRoot>/<execId>}。 */
    public Path executionDirFor(long executionId) {
        return executionsRoot().resolve(String.valueOf(executionId));
    }

    /** 制品目录：{@code <executionsRoot>/<execId>/artifacts}。调用方负责 {@code createDirectories}。 */
    public Path artifactsDirFor(long executionId) {
        return executionDirFor(executionId).resolve(ARTIFACTS_SUBDIR);
    }

    /** 输入目录：{@code <executionsRoot>/<execId>/input}。调用方负责 {@code createDirectories}。 */
    public Path inputDirFor(long executionId) {
        return executionDirFor(executionId).resolve(INPUT_SUBDIR);
    }

    /** 待上传根：{@code <dataRoot>/uploads/<token>}。 */
    public Path pendingUploadDir(String token) {
        return dataRoot().resolve(PENDING_UPLOAD_SUBDIR).resolve(token);
    }

    /** 待上传根：{@code <dataRoot>/uploads}。 */
    public Path pendingUploadsRoot() {
        return dataRoot().resolve(PENDING_UPLOAD_SUBDIR);
    }

    // ----------------------------------------------------------------------
    // 安全校验
    // ----------------------------------------------------------------------

    /**
     * 断言 {@code child} 在 {@code controlledRoot} 之下（按 {@code toAbsolutePath().normalize()}
     * 比较前缀），否则抛 {@link BusinessException}。适用于路径尚未在磁盘上、只需语法层校验的场景。
     *
     * @param child 待校验路径
     * @param controlledRoot 受控根
     * @param what 路径语义（用于错误信息）
     */
    public void assertInside(Path child, Path controlledRoot, String what) {
        if (child == null || controlledRoot == null) {
            throw new BusinessException(BusinessErrorCode.PATH_INVALID, what + " 路径为空");
        }
        Path absChild = child.toAbsolutePath().normalize();
        Path absRoot = controlledRoot.toAbsolutePath().normalize();
        if (!absChild.startsWith(absRoot)) {
            log.warn("路径越界 {} child={} root={}", what, absChild, absRoot);
            throw new BusinessException(BusinessErrorCode.PATH_ESCAPE,
                    what + " 路径越出受控目录: " + absChild);
        }
    }

    /**
     * 强安全版：使用 {@link Path#toRealPath(LinkOption...)} 解析符号链接后比较前缀。
     * 适用于清理 / 删除场景，避免攻击者构造软链越过 controlledRoot。
     * 若任一路径不存在，返回 false（不抛异常，由调用方决定跳过还是失败）。
     *
     * @param child 待校验路径
     * @param controlledRoot 受控根
     * @return true 表示确实在受控目录下
     */
    public boolean isInsideReal(Path child, Path controlledRoot) {
        if (child == null || controlledRoot == null) return false;
        try {
            // NOFOLLOW_LINKS：不要解析链接的"真实"目标，避免 TOCTOU 绕过
            Path realChild = child.toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realRoot = controlledRoot.toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS);
            return realChild.startsWith(realRoot);
        } catch (IOException ioe) {
            return false;
        }
    }

    // ----------------------------------------------------------------------
    // 工具方法
    // ----------------------------------------------------------------------

    /** 安全列出目录内容；目录不存在时返回空列表。 */
    public java.util.List<Path> safeList(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return java.util.Collections.emptyList();
        try (var stream = Files.list(dir)) {
            return stream.toList();
        }
    }
}