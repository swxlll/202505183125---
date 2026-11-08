package com.accountbook;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 统计总览 + 变体 B：多账户余额、转账、退出前一致性校验 */
public class AccountService {

    /** 各账户余额 = 收入合计 - 支出合计 */
    public Map<String, BigDecimal> balances(List<Bill> bills) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (String a : new String[]{"微信", "支付宝", "校园卡", "银行卡"}) map.put(a, BigDecimal.ZERO);
        for (Bill b : bills) {
            map.merge(b.getAccount(), b.signedAmount(), BigDecimal::add);
        }
        return map;
    }

    /** 统计总览：去重后的收入合计、支出合计、结余 */
    public void printSummary(List<Bill> bills) {
        BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
        for (Bill b : bills) {
            if ("收入".equals(b.getType())) income = income.add(b.getAmount());
            else expense = expense.add(b.getAmount());
        }
        System.out.println("===== 统计总览（去重后） =====");
        System.out.println("收入合计: " + income.toPlainString());
        System.out.println("支出合计: " + expense.toPlainString());
        System.out.println("结余    : " + income.subtract(expense).toPlainString());
    }

    /** 变体 B：按账户分别统计余额 */
    public void printBalances(List<Bill> bills) {
        System.out.println("===== 各账户余额 =====");
        balances(bills).forEach((k, v) ->
                System.out.println(k + ": " + v.toPlainString()));
    }

    /**
     * 支出按类别汇总，金额降序。
     * 用 LinkedHashMap 保序，保证每次打印顺序一致（截图对比、答辩复现都稳定）。
     */
    public Map<String, BigDecimal> expenseByCategory(List<Bill> bills) {
        Map<String, BigDecimal> map = new LinkedHashMap<String, BigDecimal>();
        for (Bill b : bills) {
            if ("支出".equals(b.getType())) {
                map.merge(b.getCategory(), b.getAmount(), BigDecimal::add);
            }
        }
        java.util.List<Map.Entry<String, BigDecimal>> list =
                new java.util.ArrayList<Map.Entry<String, BigDecimal>>(map.entrySet());
        list.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        Map<String, BigDecimal> sorted = new LinkedHashMap<String, BigDecimal>();
        for (Map.Entry<String, BigDecimal> e : list) sorted.put(e.getKey(), e.getValue());
        return sorted;
    }

    /** 按月汇总：key = yyyy-MM（TreeMap 天然按月份升序），value = {收入, 支出} */
    public Map<String, BigDecimal[]> byMonth(List<Bill> bills) {
        Map<String, BigDecimal[]> map = new java.util.TreeMap<String, BigDecimal[]>();
        for (Bill b : bills) {
            String key = b.getDate().toString().substring(0, 7); // ISO 日期固定 yyyy-MM-dd，取前 7 位即月份
            BigDecimal[] v = map.get(key);
            if (v == null) {
                v = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
                map.put(key, v);
            }
            if ("收入".equals(b.getType())) v[0] = v[0].add(b.getAmount());
            else v[1] = v[1].add(b.getAmount());
        }
        return map;
    }

    /** 打印"支出类别占比"（Top N，N<=0 表示全部），占比 = 类别金额 / 支出总额 */
    public void printCategoryTop(List<Bill> bills, int topN) {
        Map<String, BigDecimal> map = expenseByCategory(bills);
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : map.values()) total = total.add(v);

        System.out.println("===== 支出类别汇总 =====");
        if (map.isEmpty()) {
            System.out.println("（暂无支出记录）");
            return;
        }
        System.out.println(String.format("%-8s %12s %8s", "类别", "金额", "占比"));
        int i = 0;
        for (Map.Entry<String, BigDecimal> e : map.entrySet()) {
            if (topN > 0 && i++ >= topN) break;
            System.out.println(String.format("%-8s %12s %7s%%",
                    e.getKey(), e.getValue().toPlainString(), percent(e.getValue(), total)));
        }
        System.out.println("合计     " + String.format("%12s", total.toPlainString()));
    }

    /** 打印按月收支表，便于看"哪个月花超了" */
    public void printMonthly(List<Bill> bills) {
        System.out.println("===== 按月汇总 =====");
        Map<String, BigDecimal[]> map = byMonth(bills);
        if (map.isEmpty()) {
            System.out.println("（暂无记录）");
            return;
        }
        System.out.println(String.format("%-9s %12s %12s %12s", "月份", "收入", "支出", "结余"));
        for (Map.Entry<String, BigDecimal[]> e : map.entrySet()) {
            BigDecimal in = e.getValue()[0], out = e.getValue()[1];
            System.out.println(String.format("%-9s %12s %12s %12s",
                    e.getKey(), in.toPlainString(), out.toPlainString(),
                    in.subtract(out).toPlainString()));
        }
    }

    /** 百分比：保留 1 位小数；分母为 0 时返回 "0.0"，避免 ArithmeticException */
    private static String percent(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0) return "0.0";
        return part.multiply(BigDecimal.valueOf(100))
                .divide(total, 1, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    /**
     * 变体 B-ii：账户间转账。生成两笔对应记录（转出账户一笔支出、转入账户一笔收入），
     * 不直接修改余额数字。返回生成的两笔账单，调用方负责加入账单列表。
     */
    public static Bill[] transfer(int newId, java.time.LocalDate date,
                                  String from, String to, BigDecimal amount, String note) {
        Bill out = new Bill(newId, date, from, "支出", "转账", amount, "转出->" + to + " " + note, false);
        Bill in = new Bill(newId + 1, date, to, "收入", "转账", amount, "转入<- " + from + " " + note, false);
        return new Bill[]{out, in};
    }

    /**
     * 变体 B-iii：退出前一致性校验（真校验）。
     * 校验 各账户当前余额 = 基线余额 + 本次会话变动（手动记账/转账等新增记录）。
     * 基线为"最后一次导入完成后的快照"，因此导入本身不会被误判为变动。
     */
    public boolean checkConsistency(List<Bill> current, List<Bill> baseline) {
        Map<String, BigDecimal> now = balances(current);
        Map<String, BigDecimal> base = balances(baseline);
        // 本次会话相对基线新增的账单（用对象身份判断：基线中的对象不再计入变动）
        Map<String, BigDecimal> delta = new LinkedHashMap<>();
        for (Bill b : current) {
            boolean inBaseline = false;
            for (Bill o : baseline) if (o == b) { inBaseline = true; break; }
            if (!inBaseline) delta.merge(b.getAccount(), b.signedAmount(), BigDecimal::add);
        }
        boolean ok = true;
        System.out.println("===== 退出前一致性校验 =====");
        System.out.println("校验公式：当前余额 = 基线余额 + 本次变动");
        for (String account : now.keySet()) {
            BigDecimal baseAmt = base.getOrDefault(account, BigDecimal.ZERO);
            BigDecimal deltaAmt = delta.getOrDefault(account, BigDecimal.ZERO);
            BigDecimal expect = baseAmt.add(deltaAmt);
            boolean same = now.get(account).compareTo(expect) == 0;
            if (!same) ok = false;
            System.out.println(account + ": 当前余额=" + now.get(account).toPlainString()
                    + " | 基线=" + baseAmt.toPlainString()
                    + " 本次变动=" + (deltaAmt.signum() >= 0 ? "+" : "") + deltaAmt.toPlainString()
                    + " 合计=" + expect.toPlainString()
                    + (same ? " [通过]" : " [不一致！]"));
        }
        System.out.println(ok ? "结论：各账户账实相符，校验通过。" : "结论：存在不一致，请检查数据！");
        return ok;
    }

    /** 兼容旧调用（无初始快照时退化为空初始） */
    public boolean checkConsistency(List<Bill> current) {
        return checkConsistency(current, java.util.Collections.<Bill>emptyList());
    }
}
