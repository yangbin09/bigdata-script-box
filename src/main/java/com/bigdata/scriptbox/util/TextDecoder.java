package com.bigdata.scriptbox.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 宽容的文本文件解码。
 *
 * <p>脚本输出（stdout / stderr / result.json）的编码不由我们完全掌控：
 * <ul>
 *   <li>Linux 生产环境下通常是 UTF-8；</li>
 *   <li>Windows 上 Git Bash 的 {@code bash.exe} 在重定向句柄上会写 <b>UTF-16LE</b>
 *       （带 {@code FF FE} BOM），且不受 {@code LANG} / {@code LC_ALL} / JVM
 *       {@code file.encoding} 影响 —— 这是它的实现细节；</li>
 *   <li>即使 UTF-8，也可能从多字节字符中间被截断。</li>
 * </ul>
 *
 * <p>因此读取日志时**绝不能**用严格解码（{@code Files.readString} 会抛
 * {@link java.nio.charset.MalformedInputException}）：那会让"脚本跑成功了但日志页报错"。
 * 本类按 BOM 嗅探编码，并对无法解码的字节使用替换字符。
 */
public final class TextDecoder {

    private TextDecoder() {}

    /**
     * 读取文件并按 BOM 嗅探编码，非法字节替换为 {@code U+FFFD}。
     *
     * @param path 文件路径
     * @return 解码后的文本；文件不存在或读取失败时返回空串
     */
    public static String readLenient(Path path) {
        if (path == null) return "";
        try {
            if (!Files.exists(path)) return "";
            return decodeLenient(Files.readAllBytes(path));
        } catch (IOException ioe) {
            return "";
        }
    }

    /**
     * 按 BOM 嗅探解码字节数组；无 BOM 时按 UTF-8 处理。
     * 任何无法解码的字节序列使用替换字符，绝不抛异常。
     */
    public static String decodeLenient(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                charset = StandardCharsets.UTF_16LE;
                offset = 2;
            } else if (b0 == 0xFE && b1 == 0xFF) {
                charset = StandardCharsets.UTF_16BE;
                offset = 2;
            } else if (b0 == 0xEF && b1 == 0xBB
                    && bytes.length >= 3 && (bytes[2] & 0xFF) == 0xBF) {
                charset = StandardCharsets.UTF_8;
                offset = 3;
            }
        }
        return decode(bytes, offset, charset);
    }

    private static String decode(byte[] bytes, int offset, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            CharBuffer out = decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return out.toString();
        } catch (CharacterCodingException e) {
            // REPLACE 策略下理论上不会走到这里；兜底不抛。
            return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
        }
    }
}
