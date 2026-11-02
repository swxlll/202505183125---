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
     * 校验 各账户当前余额 = 初始余额(程序启动时的余额快照) + 本次会话新增账单的收支合计。
     * 若两条独立路径算出结果不一致，说明程序内部出现了数据漂移，必须提示。
     */
    public boolean checkConsistency(List<Bill> current, List<Bill> initial) {
        Map<String, BigDecimal> now = balances(current);
        Map<String, BigDecimal> base = balances(initial);
        // 本次会话新增（或未被快照包含）的账单
        Map<String, BigDecimal> delta = new LinkedHashMap<>();
        for (Bill b : current) {
            boolean inInitial = false;
            for (Bill o : initial) if (o == b) { inInitial = true; break; }
            if (!inInitial) delta.merge(b.getAccount(), b.signedAmount(), BigDecimal::add);
        }
        boolean ok = true;
        System.out.println("===== 退出前一致性校验 =====");
        for (String account : now.keySet()) {
            BigDecimal expect = base.getOrDefault(account, BigDecimal.ZERO)
                    .add(delta.getOrDefault(account, BigDecimal.ZERO));
            boolean same = now.get(account).compareTo(expect) == 0;
            if (!same) ok = false;
            System.out.println(account + ": 余额=" + now.get(account).toPlainString()
                    + " | 初始余额 + 本次收支 = " + expect.toPlainString()
                    + (same ? " 校验通过" : " 【不一致！】"));
        }
        return ok;
    }

    /** 兼容旧调用（无初始快照时退化为空初始） */
    public boolean checkConsistency(List<Bill> current) {
        return checkConsistency(current, java.util.Collections.<Bill>emptyList());
    }
}
