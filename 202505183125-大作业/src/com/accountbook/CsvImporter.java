package com.accountbook;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 导入 + 清洗（变体 B 规则）。
 * 清洗规则（与任务书 4.2 一一对应）：
 *  1. 列数 != 8 丢弃（千分位逗号会把金额切成两列，也属于此类，不能去逗号后收下）；
 *  2. 金额不是非负数字（负数/非数字文本）丢弃；
 *  3. 日期无法按三种格式解析丢弃（STRICT 模式，2026-13-40 会被拒）；
 *  4. 类型不是"收入/支出"丢弃；
 *  5. 变体 B 去重：id 相同视为重复，保留文件中出现顺序靠后的一条；
 *  6. 所有丢弃逐行记录原因；空行静默跳过。
 *
 * <p>编码：优先 UTF-8，遇到 GBK 保存的文件自动回退（见 {@link TextIO}），不会整份变乱码。
 */
public class CsvImporter {

    /** 表头列名特征（中英文两套都认）：第一列不是数字且出现这些词，才判定为表头 */
    private static final String[] HEADER_HINTS = {
            "id", "date", "account", "type", "category", "amount", "note", "recurring",
            "日期", "账户", "类型", "类别", "金额", "备注"};

    public ImportResult importFile(String path) throws IOException {
        ImportResult result = new ImportResult();

        Charset cs = TextIO.detectCharset(Paths.get(path));//先探编码
        if (!StandardCharsets.UTF_8.equals(cs)) {
            System.out.println("[提示] 检测到 " + path + " 不是 UTF-8 编码（应为 " + cs
                    + "，常见于记事本/Excel 另存为 ANSI），已按 " + cs + " 读取，结果不受影响。");
        }

        try (BufferedReader reader = TextIO.newBufferedReader(Paths.get(path))) {
            String line;
            int lineNo = 0;
            boolean headerChecked = false;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.trim().isEmpty()) continue;       // 空行：静默跳过

                // 表头只在第一行且确实含表头特征词时跳过
                if (!headerChecked) {
                    headerChecked = true;
                    if (looksLikeHeader(line)) continue;
                }

                List<String> cols = splitCsvLine(line);
                result.totalLines++;
                String idShown = cols.isEmpty() ? "?" : cols.get(0);

                // 规则1：列数必须等于 8
                if (cols.size() != 8) {
                    result.addDiscard(lineNo, idShown, "列数=" + cols.size() + "（应为8）");
                    continue;
                }
                String id = cols.get(0).trim();
                String dateStr = cols.get(1);
                String account = cols.get(2).trim();
                String type = cols.get(3).trim();
                String category = cols.get(4).trim();
                String amountStr = cols.get(5);
                String note = cols.get(6);
                String recurring = cols.get(7).trim();

                // 规则0：id 必须是数字且在 int 范围内（超范围会导致 parseInt 抛异常中断导入）
                if (!id.matches("\\d+") || id.length() > 10) {
                    result.addDiscard(lineNo, id, "id 不是合法数字（" + id + "）");
                    continue;
                }
                long idVal = Long.parseLong(id);
                if (idVal > Integer.MAX_VALUE) {
                    result.addDiscard(lineNo, id, "id 超出范围（" + id + "）");
                    continue;
                }
                // 规则2：金额必须是非负数字
                BigDecimal amount = ImportResult.parseAmount(amountStr);
                if (amount == null) {
                    result.addDiscard(lineNo, id, "金额不是非负数字（" + amountStr + "）");
                    continue;
                }
                // 规则3：日期三种格式解析
                java.time.LocalDate date = DateUtil.parse(dateStr);
                if (date == null) {
                    result.addDiscard(lineNo, id, "日期无法解析（" + dateStr + "）");
                    continue;
                }
                // 规则4：类型
                if (!"收入".equals(type) && !"支出".equals(type)) {
                    result.addDiscard(lineNo, id, "类型非法（" + type + "）");
                    continue;
                }
                // 规则5（补充）：账户为空无法归属，按脏数据丢弃（否则会在余额表里多出一个空账户）
                if (account.isEmpty()) {
                    result.addDiscard(lineNo, id, "账户为空");
                    continue;
                }
                boolean rec = "Y".equalsIgnoreCase(recurring);
                result.bills.add(new Bill((int) idVal, date, account, type,
                        category, amount, note.trim(), rec));
            }
        }

        // 规则5（变体B）：按 id 去重，保留出现顺序靠后的
        dedupKeepLater(result);
        return result;
    }

    /** 是否为表头行：第一列不是纯数字，且整行含至少一个表头特征词 */
    private boolean looksLikeHeader(String line) {
        List<String> cols = splitCsvLine(line);
        if (cols.isEmpty()) return false;
        String first = cols.get(0).trim();
        if (first.matches("\\d+")) return false;   // 第一列是数字 -> 这是数据行，不能当表头吃掉
        String lower = line.toLowerCase();
        for (String hint : HEADER_HINTS) {
            if (lower.contains(hint)) return true;
        }
        return false;
    }

    /**
     * CSV 切分：支持 RFC4180 的双引号包裹字段（内部可含逗号、双写引号）。
     * 本数据没有引号字段，走的就是"按逗号直切"这条路径，结果与直切一致；
     * 但这样备注里带逗号的行也不会被误判成"列数=9"。
     */
    private List<String> splitCsvLine(String line) {
        List<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuote) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');   // 双写引号 -> 一个字面引号
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"' && cur.length() == 0) {
                inQuote = true;            // 字段以引号开头才进入引号模式
            } else if (ch == ',') {
                cols.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cols.add(cur.toString());
        return cols;
    }

    /**
     * 变体B去重：id 相同视为重复，保留文件中出现顺序靠后的一条。
     * 正向扫描 + 原位替换，保持文件顺序，消息按出现顺序输出。
     */
    private void dedupKeepLater(ImportResult result) {
        Map<Integer, Integer> pos = new LinkedHashMap<Integer, Integer>(); // id -> kept 中下标
        List<Bill> kept = new ArrayList<Bill>();
        for (Bill b : result.bills) {
            Integer prev = pos.get(b.getId());
            if (prev == null) {
                pos.put(b.getId(), kept.size());
                kept.add(b);
            } else {
                Bill older = kept.get(prev);
                // 分两行输出，避免单行过长在控制台/截图里被截断
                result.dedupReasons.add(String.format(
                        "id=%d 重复（变体B：保留靠后），删除靠前的 %s %s",
                        b.getId(), older.getDate(), older.getAmount().toPlainString()));
                result.dedupReasons.add(String.format(
                        "    保留靠后的 %s %s %s", b.getDate(),
                        b.getAmount().toPlainString(), b.getNote()));
                result.dedupRemoved++;
                kept.set(prev, b); // 原位替换为靠后的一条
            }
        }
        result.bills.clear();
        result.bills.addAll(kept);
    }
}
