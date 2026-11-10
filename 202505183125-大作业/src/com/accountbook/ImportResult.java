package com.accountbook;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 一次导入的结果：统计数字 + 每行丢弃原因（答辩重点） */
public class ImportResult {
    public int totalLines;                 // 总行数（不含表头、空行）
    public final List<Bill> bills = new ArrayList<>();
    public final List<String> discardReasons = new ArrayList<>(); // "第N行 id=xxxx 原因"

    public int discarded() { return discardReasons.size(); }

    public void addDiscard(int lineNo, String id, String reason) {
        discardReasons.add(String.format("第%d行 id=%s：%s", lineNo, id, reason));
    }

    /** 去重阶段单独报告（不属于"脏数据丢弃"，单独列出更清晰） */
    public final List<String> dedupReasons = new ArrayList<>();
    /** 被去重删除的实际行数（一条删除可能对应多行说明文字） */
    public int dedupRemoved;

    public void printReport() {
        System.out.println("===== 导入结果 =====");
        System.out.println("总行数(不含表头): " + totalLines);
        System.out.println("脏数据丢弃: " + discarded() + " 行");
        for (String r : discardReasons) System.out.println("  [丢弃] " + r);
        System.out.println("去重删除: " + dedupRemoved + " 行");
        for (String r : dedupReasons) System.out.println("  [去重] " + r);
        System.out.println("最终有效账单: " + bills.size() + " 条");
    }

    /** 金额上限：超过一万亿基本不可能是正常记账，按非法处理（防止异常数据把统计撑爆） */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

    /** 简单校验用：金额是否为合法非负数字（最多两位小数） */
    public static BigDecimal parseAmount(String s) {
        if (s == null || s.isEmpty()) return null;
        String t = s.trim();
        if (!t.matches("\\d+(\\.\\d{1,2})?")) return null; // 拒绝负数、非数字、超两位小数
        try {
            BigDecimal v = new BigDecimal(t);
            return v.compareTo(MAX_AMOUNT) > 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
