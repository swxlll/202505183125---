package com.accountbook;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 账单查询条件：控制台与图形界面<b>共用同一套筛选逻辑</b>，避免两处实现不一致。
 *
 * <p>设计要点：
 * <ul>
 *   <li>每个条件为 null 表示"不限制"，因此 {@link #isEmpty()} 为真时等价于"全部账单"；</li>
 *   <li>账户、类型是精确匹配，类别、备注是"包含"匹配（忽略大小写，支持只输一个字）；</li>
 *   <li>日期区间为闭区间，起止都可单独省略；</li>
 *   <li>{@link #apply(List)} 返回<b>排序后的新列表</b>，不修改入参，避免调用方数据被打乱。</li>
 * </ul>
 */
public class BillQuery {

    private String account;
    private String type;
    private String category;
    private String keyword;
    /** 全局搜索词：备注 / 类别 / 账户 任一包含即命中（图形界面搜索框用） */
    private String search;
    private LocalDate from;
    private LocalDate to;

    public BillQuery account(String v)  { this.account  = blankToNull(v); return this; }
    public BillQuery type(String v)     { this.type     = blankToNull(v); return this; }
    public BillQuery category(String v) { this.category = blankToNull(v); return this; }
    public BillQuery keyword(String v)  { this.keyword  = blankToNull(v); return this; }
    public BillQuery search(String v)   { this.search   = blankToNull(v); return this; }

    /** 日期区间：起止任一可为 null（表示该端不限）；两者都有且起 > 止时交换，避免用户填反查不到数据 */
    public BillQuery range(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            LocalDate t = from; from = to; to = t;
        }
        this.from = from;
        this.to = to;
        return this;
    }

    public boolean isEmpty() {
        return account == null && type == null && category == null
                && keyword == null && search == null && from == null && to == null;
    }

    /** 单条账单是否命中全部条件（各条件之间是"与"关系） */
    public boolean matches(Bill b) {
        if (account != null && !account.equals(b.getAccount())) return false;
        if (type != null && !type.equals(b.getType())) return false;
        if (category != null && !contains(b.getCategory(), category)) return false;
        if (keyword != null && !contains(b.getNote(), keyword)) return false;
        if (search != null && !(contains(b.getNote(), search) || contains(b.getCategory(), search)
                || contains(b.getAccount(), search))) return false;
        if (from != null && b.getDate().isBefore(from)) return false;
        if (to != null && b.getDate().isAfter(to)) return false;
        return true;
    }

    /** 过滤 + 按日期、id 排序，返回新列表（不改动原列表） */
    public List<Bill> apply(List<Bill> source) {
        List<Bill> out = new ArrayList<Bill>();
        if (source == null) return out;
        for (Bill b : source) if (matches(b)) out.add(b);
        out.sort(Comparator.comparing(Bill::getDate).thenComparing(Bill::getId));
        return out;
    }

    /** 条件的可读描述，用于界面标题（让用户随时知道"现在看的是筛选后的数据"） */
    public String describe() {
        if (isEmpty()) return "全部账单";
        List<String> parts = new ArrayList<String>();
        if (search != null) parts.add("搜索「" + search + "」");
        if (account != null) parts.add("账户=" + account);
        if (type != null) parts.add("类型=" + type);
        if (category != null) parts.add("类别含「" + category + "」");
        if (keyword != null) parts.add("备注含「" + keyword + "」");
        if (from != null && to != null) parts.add(from + " 至 " + to);
        else if (from != null) parts.add(from + " 起");
        else if (to != null) parts.add("截至 " + to);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static boolean contains(String text, String part) {
        // 用 Locale.ROOT：土耳其语等区域会把 I 转成 ı，导致搜索匹配不上
        return text != null && text.toLowerCase(java.util.Locale.ROOT)
                .contains(part.toLowerCase(java.util.Locale.ROOT));
    }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
