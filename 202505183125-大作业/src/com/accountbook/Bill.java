package com.accountbook;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一条账单记录（不可变对象）。
 * 金额使用 BigDecimal，保证统计精度（避免 double 浮点误差）。
 */
public class Bill {
    private final int id;
    private final LocalDate date;
    private final String account;   // 微信/支付宝/校园卡/银行卡
    private final String type;      // 收入/支出
    private final String category;  // 中文类别
    private final BigDecimal amount; // 恒为非负数
    private final String note;
    private final boolean recurring; // Y/N

    public Bill(int id, LocalDate date, String account, String type,
                String category, BigDecimal amount, String note, boolean recurring) {
        this.id = id;
        this.date = date;
        this.account = account;
        this.type = type;
        this.category = category;
        this.amount = amount;
        this.note = note;
        this.recurring = recurring;
    }

    public int getId()            { return id; }
    public LocalDate getDate()    { return date; }
    public String getAccount()    { return account; }
    public String getType()       { return type; }
    public String getCategory()   { return category; }
    public BigDecimal getAmount() { return amount; }
    public String getNote()       { return note; }
    public boolean isRecurring()  { return recurring; }

    /** 收入返回 +amount，支出返回 -amount，用于余额计算 */
    public BigDecimal signedAmount() {
        return "收入".equals(type) ? amount : amount.negate();
    }

    @Override
    public String toString() {
        return String.format("%-5d %s %-5s %-3s %-4s %10s  %s",
                id, date, account, type, category, amount.toPlainString(), note);
    }
}
