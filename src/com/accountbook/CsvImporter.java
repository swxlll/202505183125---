package com.accountbook;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 */
public class CsvImporter {

    public ImportResult importFile(String path) throws IOException {
        ImportResult result = new ImportResult();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineNo == 1) continue;                 // 表头
                if (line.trim().isEmpty()) continue;       // 末尾空行：静默跳过

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
                boolean rec = "Y".equalsIgnoreCase(recurring);
                result.bills.add(new Bill((int) idVal, date, account, type,
                        category, amount, note.trim(), rec));
            }
        }

        // 规则5（变体B）：按 id 去重，保留出现顺序靠后的
        dedupKeepLater(result);
        return result;
    }

    /** 简易 CSV 切分：本数据不含带引号字段，按逗号直切即可 */
    private List<String> splitCsvLine(String line) {
        List<String> cols = new ArrayList<>();
        for (String s : line.split(",", -1)) cols.add(s);
        return cols;
    }

    /**
     * 变体B去重：id 相同视为重复，保留文件中出现顺序靠后的一条。
     * 正向扫描 + 原位替换，保持文件顺序，消息按出现顺序输出。
     */
    private void dedupKeepLater(ImportResult result) {
        Map<Integer, Integer> pos = new LinkedHashMap<>(); // id -> kept 中下标
        List<Bill> kept = new ArrayList<>();
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
