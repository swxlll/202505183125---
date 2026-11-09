package com.accountbook;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;

/**
 * 程序入口：菜单循环 + 手动记账输入校验 + 变体 B 功能。
 * 兼容 JDK 8+（未使用 JDK8 之后语法）。所有用户输入必须校验，非法重输，不允许崩溃。
 */
public class Main {
    /** 常量集中定义，避免魔法数字（评分项：工程质量） */
    private static final int PAGE_SIZE = 10;          // 账单浏览每页条数
    private static final int BASE_ID = 1000;          // 自动生成 id 的基准
    private static final int MENU_MIN = 0, MENU_MAX = 9;
    private static final String[] ACCOUNTS = {"微信", "支付宝", "校园卡", "银行卡"};
    private static final String[] TYPES = {"收入", "支出"};
    private static final String[] YES_NO = {"Y", "N"};

    private static final Scanner SC = new Scanner(System.in);
    private static final DateTimeFormatter IN_DATE =
            DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT);

    /** EOF/ Ctrl+D 信号：用于在任何输入点安全退出（保存后退出，不丢数据） */
    private static final class EofSignal extends RuntimeException {}

    /** 退出时需要保存的上下文（仅 main 循环内赋值） */
    private static Storage storage;
    private static List<Bill> bills;

    public static void main(String[] args) {
        // 控制台编码自适应：Windows cmd 默认 GBK(936)，Linux/macOS 默认 UTF-8。
        // 优先使用启动参数 -Dstdout.encoding=xxx 指定的编码，其次按操作系统选择，
        // 避免"强制 UTF-8 输出到 GBK 控制台"导致的乱码。
        System.setOut(makeOut());

        Config config;
        try {
            config = new Config("config.properties");
        } catch (IOException e) {
            System.out.println("[错误] 无法读取 config.properties: " + e.getMessage());
            return;
        }
        // 加分项 9.1：把天气配置注入教师类（必须在任何天气查询之前执行）
        WeatherService.install(config);

        storage = new Storage(config.getStorePath());
        bills = new ArrayList<Bill>(storage.load()); // 启动时先加载持久化数据
        if (!bills.isEmpty()) {
            System.out.println("（已加载历史数据 " + bills.size() + " 条："
                    + config.getStorePath() + (storage.getLastSkippedLines() > 0
                    ? "，另有 " + storage.getLastSkippedLines() + " 行损坏已跳过" : "") + "）");
        }
        // 初始快照：基线为"启动时的持久化数据"，导入完成后会刷新为"导入后的数据"，
        // 使退出校验只针对本次会话真正新增的变动（手动记账/转账），而不是导入的数据。
        final List<Bill> initialBills = new ArrayList<Bill>(bills);

        if (System.getProperty("os.name", "").toLowerCase().contains("win")
                && "GBK".equalsIgnoreCase(System.getProperty("stdout.encoding", "GBK")) == false) {
            // 用户手动指定了非 GBK 编码，但 Windows 控制台可能仍是 936，给出提示
            System.out.println("[提示] 若中文乱码，请用 start.bat 启动，或执行 chcp 65001 后重试。");
        }

        while (true) {
            try {
                printMenu(config);
                int choice = readInt("请选择: ", MENU_MIN, MENU_MAX);
                switch (choice) {
                    case 1: { // 导入数据
                        CsvImporter importer = new CsvImporter();
                        ImportResult r = importer.importFile(config.getCsvPath());
                        r.printReport();
                        // 与已持久化的数据合并（导入行 id 不覆盖已有）
                        bills = Storage.merge(bills, r.bills);
                        // 导入的数据视为基线下限：刷新快照，使退出校验只针对本次会话的后续变动
                        initialBills.clear();
                        initialBills.addAll(bills);
                        System.out.println("（一致性校验基线已更新为导入后的 "
                                + bills.size() + " 条账单）");
                        break;
                    }
                    case 2: browse(bills); break;      // 账单浏览
                    case 3: addManual(bills, config); break;   // 手动记账
                    case 4: { // 统计总览 + 明细报表 + 可选导出（加分项 9.1：附带实时天气）
                        printWeather(config, "统计页");
                        AccountService svc = new AccountService();
                        svc.printSummary(bills);
                        svc.printMonthly(bills);
                        svc.printCategoryTop(bills, 5);
                        svc.printBalances(bills);
                        askExport(bills);
                        break;
                    }
                    case 5: new AccountService().printBalances(bills); break; // 变体B：账户余额
                    case 6: doTransfer(bills); break;  // 变体B：转账
                    case 7: saveAndCheck(config, storage, bills, initialBills); break; // 保存+一致性校验
                    case 8: about(config); break;      // 关于
                    case 9: // 加分项 9.2：图形界面（关闭窗口后回到控制台，数据重新加载）
                        saveQuietly();
                        if (AccountBookFrame.show(config, storage)) {
                            bills = new ArrayList<Bill>(storage.load());
                            initialBills.clear();
                            initialBills.addAll(bills);
                            System.out.println("（已从 " + config.getStorePath()
                                    + " 重新加载 " + bills.size() + " 条账单）");
                        }
                        break;
                    case 0: // 退出：先校验再保存（保存失败也要给出提示，不允许崩溃）
                        new AccountService().checkConsistency(bills, initialBills);
                        saveQuietly();
                        System.out.println("数据已保存，再见！");
                        return;
                    default: // 理论上不可达（readInt 已限定范围）
                        System.out.println("未知选项。");
                }
            } catch (EofSignal eof) { // 任何输入点遇到 EOF：保存后安全退出
                saveQuietly();
                System.out.println();
                System.out.println("[提示] 输入已关闭，数据已保存，程序退出。");
                return;
            } catch (IOException e) { // 导入/保存的 IO 异常：报告后回菜单，不崩溃
                System.out.println("[IO错误] " + e.getMessage() + "，请检查文件路径后重试。");
            } catch (Exception e) { // 其余异常兜底（message 为 null 时显示异常类名）
                String msg = e.getMessage();
                System.out.println("[错误] " + (msg != null ? msg : e.getClass().getSimpleName())
                        + "，请重试。");
            }
        }
    }

    /**
     * 构造控制台输出流：编码优先级
     *   1) -Dstdout.encoding=xxx 启动参数（便于现场演示时手动指定）
     *   2) Windows → GBK（cmd 默认代码页 936）
     *   3) 其他系统 → UTF-8
     */
    private static PrintStream makeOut() {
        String enc = System.getProperty("stdout.encoding");
        if (enc == null || enc.trim().isEmpty()) {
            enc = System.getProperty("os.name", "").toLowerCase().contains("win") ? "GBK" : "UTF-8";
        }
        try {
            return new PrintStream(System.out, true, enc);
        } catch (java.io.UnsupportedEncodingException e) {
            return System.out; // 编码不可用则退回默认流，不崩溃
        }
    }

    /** 保存失败不抛出（退出路径），给出明确错误提示 */
    private static void saveQuietly() {
        try {
            storage.save(bills);
        } catch (IOException e) {
            System.out.println("[错误] 数据保存失败: " + e.getMessage() + "（本次会话的更改可能丢失）");
        }
    }

    private static void printMenu(Config config) {
        System.out.println();
        System.out.println("========== 个人记账本（" + config.getStudentId() + "） ==========");
        System.out.println("1. 导入数据   2. 账单浏览   3. 手动记账   4. 统计总览");
        System.out.println("5. 账户余额   6. 账户转账   7. 保存数据   8. 关于     0. 退出");
        System.out.println("9. 图形界面（加分项 9.2，窗口操作，控制台功能保留）");
    }

    /** 账单浏览：先筛选（可全部跳过），再按日期排序真分页（每页 PAGE_SIZE 条），空列表友好提示 */
    private static void browse(List<Bill> list) {
        if (list.isEmpty()) {
            System.out.println("（暂无账单，请先导入或手动记账）");
            return;
        }
        BillQuery query = askQuery();
        List<Bill> sorted = query.apply(list);
        if (sorted.isEmpty()) {
            System.out.println("（没有符合条件的账单：" + query.describe() + "）");
            return;
        }
        int totalPages = (sorted.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = 1;
        while (true) {
            int from = (page - 1) * PAGE_SIZE;
            int to = Math.min(from + PAGE_SIZE, sorted.size());
            System.out.println("----- 第 " + page + "/" + totalPages + " 页 | 筛选：" + query.describe()
                    + " | 共 " + sorted.size() + " 条 -----");
            System.out.println(String.format("%-6s %-11s %-6s %-4s %-6s %11s  %s",
                    "id", "日期", "账户", "类型", "类别", "金额", "备注"));
            for (int i = from; i < to; i++) System.out.println(sorted.get(i));
            System.out.println("本页小计：" + subtotal(sorted.subList(from, to)));
            System.out.print("n=下一页 p=上一页 f=首页 l=末页 g=跳页 q=返回: ");
            String cmd = readLine().trim().toLowerCase();
            if (cmd.equals("n") && page < totalPages) page++;
            else if (cmd.equals("p") && page > 1) page--;
            else if (cmd.equals("f")) page = 1;
            else if (cmd.equals("l")) page = totalPages;
            else if (cmd.equals("g")) page = readInt("跳转到第几页(1~" + totalPages + "): ", 1, totalPages);
            else if (cmd.equals("q")) return;
            else System.out.println("未知指令，可用：n / p / f / l / g / q");
        }
    }

    /** 筛选条件录入：全部可以回车跳过，默认不限制（不改变原有"直接看全部账单"的习惯） */
    private static BillQuery askQuery() {
        BillQuery q = new BillQuery();
        System.out.println("----- 账单筛选（直接回车 = 不限制）-----");
        int a = readIntAllowEmpty("账户(1微信 2支付宝 3校园卡 4银行卡 0全部): ", 0, 4);
        if (a >= 1) q.account(ACCOUNTS[a - 1]);
        int t = readIntAllowEmpty("类型(1收入 2支出 0全部): ", 0, 2);
        if (t >= 1) q.type(TYPES[t - 1]);
        String c = readLineAllowEmpty("类别关键字(回车跳过): ");
        if (!c.isEmpty()) q.category(c);
        String k = readLineAllowEmpty("备注关键字(回车跳过): ");
        if (!k.isEmpty()) q.keyword(k);
        LocalDate from = readDateAllowEmpty("起始日期(yyyy-M-d, 回车不限): ");
        LocalDate to = readDateAllowEmpty("结束日期(yyyy-M-d, 回车不限): ");
        q.range(from, to);
        return q;
    }

    /** 当前页（或筛选结果）小计，让"筛选"这件事在数字上也能自证 */
    private static String subtotal(List<Bill> part) {
        BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
        for (Bill b : part) {
            if ("收入".equals(b.getType())) income = income.add(b.getAmount());
            else expense = expense.add(b.getAmount());
        }
        return "收入 " + income.toPlainString() + " / 支出 " + expense.toPlainString()
                + " / 结余 " + income.subtract(expense).toPlainString();
    }

    /** 手动记账：逐字段输入，全部校验，非法重输 */
    private static void addManual(List<Bill> list, Config config) {
        int id;
        while (true) {
            id = readInt("编号id: ", 1, Integer.MAX_VALUE);
            if (!containsId(list, id)) break;
            System.out.println("该 id 已存在，请换一个。");
        }
        LocalDate date = readDate("日期(yyyy-M-d): ");
        String account = readChoice("账户", ACCOUNTS);
        String type = readChoice("类型", TYPES);
        String category = readNonEmpty("类别");
        BigDecimal amount = readAmount("金额(非负,最多两位小数): ");
        String note = readNonEmpty("备注");
        boolean rec = readChoice("固定支出(Y/N)", YES_NO).equals("Y");
        list.add(new Bill(id, date, account, type, category, amount, note, rec));
        System.out.println("已添加: " + id);
        printWeather(config, "已作为本次记账的备注参考");
    }

    /**
     * 加分项 9.1：展示实时天气。城市名来自 config.properties（weather.city），
     * 实际请求由教师提供的 WeatherKit.now(城市) 完成；失败时只打印一行提示，绝不中断流程。
     */
    private static void printWeather(Config config, String scene) {
        if (!config.isWeatherEnabled()) return;
        System.out.println("【实时天气 · " + scene + "】"
                + WeatherService.fetch(config.getWeatherCity()));
    }

    /** 统计页可选导出：回车默认"不导出"，避免每次看统计都在目录里堆文件 */
    private static void askExport(List<Bill> bills) {
        System.out.print("是否导出 CSV 报表？(y/N): ");
        String s = readLine().trim();
        if (!"y".equalsIgnoreCase(s)) return;
        try {
            java.nio.file.Path detail = Exporter.exportBills(bills);
            java.nio.file.Path summary = Exporter.exportSummary(bills);
            System.out.println("已导出（UTF-8 带 BOM，Excel 双击可直接打开）：");
            System.out.println("  " + detail.toAbsolutePath());
            System.out.println("  " + summary.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("[错误] 导出失败: " + e.getMessage());
        }
    }

    /** 变体B-ii：转账生成两笔记录，自动挑选未占用的连续 id */
    private static void doTransfer(List<Bill> list) {
        String from = readChoice("转出账户", ACCOUNTS);
        String to;
        do {
            to = readChoice("转入账户", ACCOUNTS);
            if (from.equals(to)) System.out.println("转出/转入不能是同一账户。");
        } while (from.equals(to));
        BigDecimal amount = readAmount("转账金额: ");
        LocalDate date = readDate("转账日期(yyyy-M-d): ");
        int maxId = BASE_ID;
        for (Bill b : list) if (b.getId() > maxId) maxId = b.getId();
        int newId = maxId + 1;
        while (containsId(list, newId) || containsId(list, newId + 1)) newId++;
        Bill[] pair = AccountService.transfer(newId, date, from, to, amount, "转账");
        list.add(pair[0]);
        list.add(pair[1]);
        System.out.println("转账完成（生成两笔记录: " + pair[0].getId() + ", " + pair[1].getId() + "）");
    }

    private static void saveAndCheck(Config config, Storage storage,
                                     List<Bill> list, List<Bill> baseline) {
        new AccountService().checkConsistency(list, baseline);
        try {
            storage.save(list);
            System.out.println("已保存到 " + config.getStorePath());
        } catch (IOException e) {
            System.out.println("[错误] 保存失败: " + e.getMessage());
        }
    }

    private static void about(Config config) {
        System.out.println("===== 关于 =====");
        System.out.println("程序名称: 个人记账本（控制台版）");
        System.out.println("作者学号: " + config.getStudentId());
        System.out.println("水印    : " + config.getWatermark());
    }

    // ---------- 输入校验工具（统一走 readLine，EOF 抛 EofSignal 由主循环统一保存退出） ----------
    private static String readLine() {
        if (!SC.hasNextLine()) throw new EofSignal();
        return SC.nextLine();
    }

    private static int readInt(String prompt, int min, int max) {
        while (true) {
            System.out.print(prompt);
            String s = readLine().trim();
            try {
                int v = Integer.parseInt(s);
                if (v >= min && v <= max) return v;
            } catch (NumberFormatException ignored) { }
            System.out.println("输入非法，请输入 " + min + "~" + max + " 的整数。");
        }
    }

    private static LocalDate readDate(String prompt) {
        while (true) {
            System.out.print(prompt);
            try {
                return LocalDate.parse(readLine().trim(), IN_DATE);
            } catch (Exception e) {
                System.out.println("日期非法，请按 yyyy-M-d 输入（如 2026-4-1）。");
            }
        }
    }

    /** 允许为空的选择项：回车返回 -1（= 不限制），非法输入重输 */
    private static int readIntAllowEmpty(String prompt, int min, int max) {
        while (true) {
            System.out.print(prompt);
            String s = readLine().trim();
            if (s.isEmpty()) return -1;
            try {
                int v = Integer.parseInt(s);
                if (v >= min && v <= max) return v;
            } catch (NumberFormatException ignored) { }
            System.out.println("输入非法，请输入 " + min + "~" + max + " 的整数（回车跳过）。");
        }
    }

    /** 允许为空的文本项 */
    private static String readLineAllowEmpty(String prompt) {
        System.out.print(prompt);
        return readLine().trim();
    }

    /** 允许为空的日期项：回车返回 null（= 该端不限），非法日期重输 */
    private static LocalDate readDateAllowEmpty(String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = readLine().trim();
            if (s.isEmpty()) return null;
            try {
                return LocalDate.parse(s, IN_DATE);
            } catch (Exception e) {
                System.out.println("日期非法，请按 yyyy-M-d 输入（如 2026-4-1），或回车跳过。");
            }
        }
    }

    private static BigDecimal readAmount(String prompt) {
        while (true) {
            System.out.print(prompt);
            BigDecimal v = ImportResult.parseAmount(readLine());
            if (v != null) return v;
            System.out.println("金额非法（须为非负数字、最多两位小数）。");
        }
    }

    private static String readNonEmpty(String name) {
        while (true) {
            System.out.print(name + ": ");
            String s = readLine().trim();
            if (!s.isEmpty()) return s;
            System.out.println(name + "不能为空。");
        }
    }

    /** 选项匹配忽略大小写（Y/y、N/n 均可），返回选项表中的规范值 */
    private static String readChoice(String name, String[] options) {
        while (true) {
            System.out.print(name + " (" + String.join("/", options) + "): ");
            String s = readLine().trim();
            for (String o : options) if (o.equalsIgnoreCase(s)) return o;
            System.out.println("输入非法。");
        }
    }

    private static boolean containsId(List<Bill> list, int id) {
        for (Bill b : list) if (b.getId() == id) return true;
        return false;
    }
}
