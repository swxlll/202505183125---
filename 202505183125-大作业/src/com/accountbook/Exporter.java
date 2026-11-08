package com.accountbook;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * CSV 报表导出（零第三方依赖）。
 *
 * <p>三个细节决定"能不能直接用 Excel 打开"：
 * <ol>
 *   <li><b>UTF-8 + BOM</b>：不带 BOM 时 Excel 双击会用 GBK 解码，中文全是乱码；</li>
 *   <li><b>字段转义</b>：备注里可能出现逗号/引号/换行，按 RFC4180 用双引号包裹并把内部引号翻倍，
 *       否则一条备注就能把整行切错列；</li>
 *   <li><b>文件名带时间戳</b>：重复导出不会覆盖上一次的结果，方便对比。</li>
 * </ol>
 */
public final class Exporter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** UTF-8 BOM：Excel 靠它识别编码 */
    private static final String BOM = "\uFEFF";

    private Exporter() { }

    /** 导出账单明细，返回生成的文件路径 */
    public static Path exportBills(List<Bill> bills) throws IOException {
        Path file = Paths.get("账单明细-" + LocalDateTime.now().format(STAMP) + ".csv");
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(BOM);
            w.write("id,日期,账户,类型,类别,金额,备注,固定支出");
            w.newLine();
            for (Bill b : bills) {
                w.write(String.join(",",
                        String.valueOf(b.getId()),
                        b.getDate().toString(),
                        esc(b.getAccount()),
                        esc(b.getType()),
                        esc(b.getCategory()),
                        b.getAmount().toPlainString(),
                        esc(b.getNote()),
                        b.isRecurring() ? "Y" : "N"));
                w.newLine();
            }
        }
        return file;
    }

    /** 导出统计汇总（类别 / 月份 / 账户 三个维度放同一张"维度,项目,金额"表，便于 Excel 透视） */
    public static Path exportSummary(List<Bill> bills) throws IOException {
        AccountService svc = new AccountService();
        Path file = Paths.get("统计汇总-" + LocalDateTime.now().format(STAMP) + ".csv");
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(BOM);
            w.write("维度,项目,金额");
            w.newLine();

            for (Map.Entry<String, BigDecimal> e : svc.expenseByCategory(bills).entrySet()) {
                w.write("支出类别," + esc(e.getKey()) + "," + e.getValue().toPlainString());
                w.newLine();
            }
            for (Map.Entry<String, BigDecimal[]> e : svc.byMonth(bills).entrySet()) {
                w.write("月份收入," + e.getKey() + "," + e.getValue()[0].toPlainString());
                w.newLine();
                w.write("月份支出," + e.getKey() + "," + e.getValue()[1].toPlainString());
                w.newLine();
            }
            for (Map.Entry<String, BigDecimal> e : svc.balances(bills).entrySet()) {
                w.write("账户余额," + esc(e.getKey()) + "," + e.getValue().toPlainString());
                w.newLine();
            }
        }
        return file;
    }

    /** RFC4180 转义：含逗号、引号、换行时用双引号包裹，内部引号翻倍 */
    private static String esc(String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
