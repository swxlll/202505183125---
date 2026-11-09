package com.accountbook;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 文本文件读取：自动识别编码，避免"用记事本另存为 GBK 之后整份数据变乱码"。
 *
 * <p>典型场景：Windows 记事本保存 .properties / .csv 时默认用 ANSI（简体中文下即 GBK），
 * 而程序按 UTF-8 硬读，于是「北京」变成「鍖椾含」，城市查不到、类型「收入/支出」也匹配不上，
 * 整份 CSV 全被判成脏数据。这里先探测再决定用哪个字符集：
 * <ol>
 *   <li>有 UTF-8 BOM（EF BB BF）→ UTF-8；</li>
 *   <li>整段字节符合 UTF-8 合法序列 → UTF-8；</li>
 *   <li>否则回退 GBK（Windows 中文环境默认编码），并给出一次提示。</li>
 * </ol>
 */
public final class TextIO {

    /** 探测时最多读取的字节数：足够覆盖配置文件，也足够判断整份 CSV 的编码 */
    private static final int SNIFF_BYTES = 64 * 1024;

    private TextIO() { }

    /** 按探测到的编码打开文本文件（流式，适合大文件） */
    public static BufferedReader newBufferedReader(Path path) throws IOException {
        Charset cs = detectCharset(path);
        return new BufferedReader(new InputStreamReader(Files.newInputStream(path), cs));
    }

    /** 按探测到的编码读全文（去掉 UTF-8 BOM，适合配置文件这种小文件） */
    public static String readAll(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String text = new String(bytes, detectCharset(bytes));
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** 读取 .properties（支持 UTF-8 / GBK 两种保存方式） */
    public static Properties readProperties(Path path) throws IOException {
        Properties props = new Properties();
        try (StringReader r = new StringReader(readAll(path))) {
            props.load(r);
        }
        return props;
    }

    /**
     * 探测文件编码。
     *
     * @return UTF-8 或 GBK
     */
    public static Charset detectCharset(Path path) throws IOException {
        byte[] head = new byte[SNIFF_BYTES];
        int n = 0;
        try (InputStream in = Files.newInputStream(path)) {
            int read;
            while (n < head.length && (read = in.read(head, n, head.length - n)) > 0) {
                n += read;
            }
        }
        return detectCharset(head, n);
    }

    private static Charset detectCharset(byte[] bytes) {
        return detectCharset(bytes, bytes.length);
    }

    private static Charset detectCharset(byte[] b, int n) {
        if (n >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        return isValidUtf8(b, n) ? StandardCharsets.UTF_8 : Charset.forName("GBK");
    }

    /**
     * 校验字节序列是否是合法 UTF-8（手写校验，不依赖库）。
     * 尾部不完整的多字节序列视为合法——那是"只读了前 N 字节"造成的截断，不是文件本身的问题。
     */
    private static boolean isValidUtf8(byte[] b, int n) {
        int i = 0;
        while (i < n) {
            int c = b[i] & 0xFF;
            int need;
            if (c < 0x80) {                       // ASCII
                i++;
                continue;
            } else if (c >= 0xC2 && c <= 0xDF) {  // 2 字节
                need = 1;
            } else if (c >= 0xE0 && c <= 0xEF) {  // 3 字节
                need = 2;
            } else if (c >= 0xF0 && c <= 0xF4) {  // 4 字节
                need = 3;
            } else {
                return false;                     // 0x80~0xC1、0xF5~0xFF 不可能是 UTF-8 首字节
            }
            if (i + need >= n) return true;       // 截断在尾部
            for (int k = 1; k <= need; k++) {
                if ((b[i + k] & 0xC0) != 0x80) return false;
            }
            i += need + 1;
        }
        return true;
    }
}
