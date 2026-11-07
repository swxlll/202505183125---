package com.accountbook;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

/**
 * 日期解析工具：数据文件中三种格式混排，都必须能解析。
 *   2026-01-05 / 2026/1/5 / 2026年1月5日
 * 使用 STRICT 模式，拒绝 2026-13-40 这类非法日期。
 */
public final class DateUtil {
    // 月/日允许 1~2 位数字（uuuu 配合 STRICT 严格校验，2026-13-40 会被拒绝）
    private static final DateTimeFormatter F_DASH = DateTimeFormatter.ofPattern("uuuu-M-d")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter F_SLASH = DateTimeFormatter.ofPattern("uuuu/M/d")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter F_CN = new DateTimeFormatterBuilder()
            .appendPattern("uuuu'年'M'月'd'日'")
            .parseDefaulting(java.time.temporal.ChronoField.ERA, 1)
            .toFormatter(Locale.CHINA)
            .withResolverStyle(ResolverStyle.STRICT);

    private DateUtil() {}

    /** 三种格式依次尝试，全部失败返回 null（由清洗逻辑丢弃该行） */
    public static LocalDate parse(String s) {
        s = s.trim();
        // 三种格式的分隔符互斥，可以按特征快速分流，避免歧义
        try {
            if (s.contains("年")) return LocalDate.parse(s, F_CN);
            if (s.contains("/")) return LocalDate.parse(s, F_SLASH);
            if (s.contains("-")) return LocalDate.parse(s, F_DASH);
        } catch (DateTimeParseException ignored) {
            // 例如 2026-13-40：格式看着像 yyyy-MM-dd，但语义非法
        }
        return null;
    }
}
