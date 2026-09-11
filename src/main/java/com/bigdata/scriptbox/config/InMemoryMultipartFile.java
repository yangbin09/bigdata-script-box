package com.bigdata.scriptbox.config;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * 内存版 {@link MultipartFile} 实现。
 *
 * <p>用途：在不经过 HTTP 的场景下（例如 {@link DataInitializer} 启动时把内置 mock
 * 脚本灌进数据库）伪造一个 MultipartFile，复用 {@code ScriptService.create} 的
 * 现有上传流程，避免在多处重复实现「写磁盘 + 检查大小」逻辑。
 *
 * <p>注意：本实现把内容常驻内存，因此只能用于小文件（如内置示例脚本）。
 */
public class InMemoryMultipartFile implements MultipartFile {

    /** 表单字段名。 */
    private final String name;
    /** 原始文件名。 */
    private final String originalFilename;
    /** MIME 类型。 */
    private final String contentType;
    /** 文件二进制内容。 */
    private final byte[] content;

    /**
     * 构造一个内存版 MultipartFile。
     *
     * @param name 表单字段名
     * @param originalFilename 原始文件名
     * @param contentType MIME 类型（如 {@code application/x-sh}）
     * @param content 文件二进制内容；为 null 时视为空内容
     */
    public InMemoryMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content == null ? new byte[0] : content;
    }

    /**
     * 获取表单字段名。
     *
     * @return 表单字段名
     */
    @Override public String getName() { return name; }

    /**
     * 获取原始文件名。
     *
     * @return 原始文件名
     */
    @Override public String getOriginalFilename() { return originalFilename; }

    /**
     * 获取 MIME 类型。
     *
     * @return MIME 类型
     */
    @Override public String getContentType() { return contentType; }

    /**
     * 是否为空内容。
     *
     * @return 内容长度为 0 时返回 true
     */
    @Override public boolean isEmpty() { return content.length == 0; }

    /**
     * 获取内容字节数。
     *
     * @return 文件字节数
     */
    @Override public long getSize() { return content.length; }

    /**
     * 获取全部内容。
     *
     * @return 字节数组
     */
    @Override public byte[] getBytes() { return content; }

    /**
     * 获取输入流。每次返回新的 {@link ByteArrayInputStream}，互不影响。
     *
     * @return 输入流
     * @throws IOException 实际不会抛出，保留以满足接口契约
     */
    @Override public InputStream getInputStream() throws IOException { return new ByteArrayInputStream(content); }

    /**
     * 把内容写入目标文件。
     *
     * @param dest 目标文件
     * @throws IOException 写入失败时抛出
     */
    @Override public void transferTo(File dest) throws IOException { java.nio.file.Files.write(dest.toPath(), content); }
}