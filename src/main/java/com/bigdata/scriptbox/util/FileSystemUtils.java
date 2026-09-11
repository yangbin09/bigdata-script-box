package com.bigdata.scriptbox.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 文件系统工具方法集合。
 *
 * <p>所有方法都遵循「不跟随软链接 + 单文件失败只 WARN 不抛出」的约定，便于
 * 清理 / 统计 / 删除等批量任务用同一种防御策略。
 */
public final class FileSystemUtils {

    private static final Logger log = LoggerFactory.getLogger(FileSystemUtils.class);

    private FileSystemUtils() {}

    /**
     * 递归统计 {@code dir} 下所有常规文件的累计字节数。
     *
     * <p>行为约定：
     * <ul>
     *   <li>不跟随符号链接 — 攻击者构造自引用环不会导致死循环；</li>
     *   <li>单文件 stat 失败只记 WARN 跳过，不让整个目录统计返回 0；</li>
     *   <li>流与句柄用 try-with-resources 释放，调用方不必手动 close。</li>
     * </ul>
     *
     * @param dir 要统计的目录（不存在则返回 0）
     * @return 字节数（不含目录元数据）
     */
    public static long directorySize(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        long total = 0;
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                try {
                    if (Files.isSymbolicLink(p)) continue;
                    if (Files.isRegularFile(p)) {
                        total += Files.size(p);
                    }
                } catch (IOException ioe) {
                    log.warn("FileSystemUtils: 统计文件大小失败 path={}: {}", p, ioe.getMessage());
                }
            }
        } catch (IOException ioe) {
            log.warn("FileSystemUtils: 遍历目录失败 dir={}: {}", dir, ioe.getMessage());
        }
        return total;
    }

    /**
     * 递归删除整个目录（叶子在前，目录最后删）。用 {@link java.nio.file.FileVisitor} 实现，
     * 删除失败记 WARN 不抛异常。
     *
     * <p>调用方应在调用前自己确认 {@code p} 已在受控目录内（{@code StoragePathService#assertInside}）。
     *
     * @param p 要删除的目录
     * @throws IOException 当根目录本身不存在或不是目录
     */
    public static void deleteRecursively(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return;
        if (Files.isSymbolicLink(p)) {
            try { Files.delete(p); } catch (IOException ioe) {
                log.warn("FileSystemUtils: 删除软链接失败 path={}: {}", p, ioe.getMessage());
            }
            return;
        }
        java.nio.file.FileVisitor<Path> visitor = new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                try { Files.delete(file); }
                catch (IOException ioe) {
                    log.warn("FileSystemUtils: 删除文件失败 path={}: {}", file, ioe.getMessage());
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("FileSystemUtils: 访问失败 path={}: {}", file, exc.getMessage());
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                try { Files.delete(dir); }
                catch (IOException ioe) {
                    log.warn("FileSystemUtils: 删除目录失败 path={}: {}", dir, ioe.getMessage());
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        };
        java.nio.file.Files.walkFileTree(p, visitor);
    }

    /** {@link LinkOption} 重导出，便于其他工具类引用。 */
    public static final LinkOption[] NOFOLLOW = { LinkOption.NOFOLLOW_LINKS };
}