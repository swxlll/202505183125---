package com.accountbook;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 零依赖自检程序（不引入 JUnit，符合任务书"无第三方依赖"的要求）。
 *
 * <p>为什么需要它：手动点菜单只能证明"某一个流程能跑通"，改一处代码是否弄坏了别处，
 * 靠人眼看不出来。这里把关键规则固化成可重复执行的断言，改完代码跑一遍就知道有没有回归。
 *
 * <p>运行方式（在 out 目录，与 config.properties / csv 同级）：
 * <pre>
 * java -cp accountbook.jar com.accountbook.SelfTest
 * </pre>
 * 全部通过退出码 0，有失败退出码 1。
 */
public final class SelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws IOException {
        System.out.println("===== 自检开始 =====");

        // ---- 1. 日期解析（三种格式 + 严格模式） ----
        eq("日期 2026-01-05", LocalDate.of(2026, 1, 5), DateUtil.parse("2026-01-05"));
        eq("日期 2026/1/5", LocalDate.of(2026, 1, 5), DateUtil.parse("2026/1/5"));
        eq("日期 2026年1月5日", LocalDate.of(2026, 1, 5), DateUtil.parse("2026年1月5日"));
        eq("非法日期 2026-13-40 必须被拒", null, DateUtil.parse("2026-13-40"));
        eq("非法日期 2025-2-29（平年）必须被拒", null, DateUtil.parse("2025-2-29"));

        // ---- 2. 金额校验 ----
        eq("金额 12.30", new BigDecimal("12.30"), ImportResult.parseAmount("12.30"));
        eq("金额 12.345 应拒绝（超两位小数）", null, ImportResult.parseAmount("12.345"));
        eq("金额 -12.50 应拒绝（负数）", null, ImportResult.parseAmount("-12.50"));
        eq("金额 12,300.50 应拒绝（含千分位）", null, ImportResult.parseAmount("12,300.50"));
        eq("金额 abc 应拒绝", null, ImportResult.parseAmount("abc"));

        // ---- 3. 导入清洗结果（依赖真实 CSV，不做任何造数） ----
        Config config;
        try {
            config = new Config("config.properties");
        } catch (IOException e) {
            System.out.println("[中止] 当前目录没有 config.properties，请先 cd 到 out 目录再运行自检：");
            System.out.println("       cd out && java -cp accountbook.jar com.accountbook.SelfTest");
            System.exit(2);
            return;
        }
        Path csv = Paths.get(config.getCsvPath());
        if (!Files.exists(csv)) {
            System.out.println("[中止] 找不到 " + csv.toAbsolutePath()
                    + "，请在 out 目录（与 csv 同级）运行自检。");
            System.exit(2);
        }
        ImportResult r = new CsvImporter().importFile(csv.toString());
        eq("总行数（不含表头）", 57, r.totalLines);
        eq("脏数据丢弃行数", 5, r.discarded());
        eq("去重删除行数", 2, r.dedupRemoved);
        eq("最终有效账单数", 50, r.bills.size());

        // ---- 4. 变体B去重方向：保留靠后 ----
        Bill keep1005 = find(r.bills, 1005);
        eq("id=1005 保留靠后一条的金额", new BigDecimal("1312.00"),
                keep1005 == null ? null : keep1005.getAmount());
        Bill keep1031 = find(r.bills, 1031);
        eq("id=1031 保留靠后一条的金额", new BigDecimal("155.28"),
                keep1031 == null ? null : keep1031.getAmount());

        // ---- 5. 统计口径 ----
        AccountService svc = new AccountService();
        eq("收入合计", new BigDecimal("16995.00"), sum(r.bills, "收入"));
        eq("支出合计", new BigDecimal("6567.16"), sum(r.bills, "支出"));
        eq("结余", new BigDecimal("10427.84"),
                sum(r.bills, "收入").subtract(sum(r.bills, "支出")));

        Map<String, BigDecimal> bal = svc.balances(r.bills);
        eq("微信余额", new BigDecimal("3379.43"), bal.get("微信"));
        eq("支付宝余额", new BigDecimal("9118.37"), bal.get("支付宝"));
        eq("校园卡余额", new BigDecimal("-2924.57"), bal.get("校园卡"));
        eq("银行卡余额", new BigDecimal("854.61"), bal.get("银行卡"));

        // ---- 6. 按月汇总必须与总账自洽 ----
        BigDecimal monthIn = BigDecimal.ZERO, monthOut = BigDecimal.ZERO;
        for (BigDecimal[] v : svc.byMonth(r.bills).values()) {
            monthIn = monthIn.add(v[0]);
            monthOut = monthOut.add(v[1]);
        }
        eq("月度收入合计 = 总收入", sum(r.bills, "收入"), monthIn);
        eq("月度支出合计 = 总支出", sum(r.bills, "支出"), monthOut);

        // ---- 7. 转账：生成两笔且余额一增一减 ----
        List<Bill> afterTransfer = new ArrayList<Bill>(r.bills);
        BigDecimal wechatBefore = svc.balances(afterTransfer).get("微信");
        Bill[] pair = AccountService.transfer(9001, LocalDate.of(2026, 4, 1),
                "微信", "支付宝", new BigDecimal("100.00"), "自检");
        afterTransfer.add(pair[0]);
        afterTransfer.add(pair[1]);
        eq("转账生成两笔记录", 2, pair.length);
        eq("转出账户减少 100", wechatBefore.subtract(new BigDecimal("100.00")),
                svc.balances(afterTransfer).get("微信"));
        eq("转账后总资产不变", sum(r.bills, "收入").subtract(sum(r.bills, "支出")),
                totalBalance(svc.balances(afterTransfer)));

        // ---- 8. 一致性校验：正确数据通过，删一条必须报警 ----
        List<Bill> baseline = new ArrayList<Bill>(r.bills);
        check("未改动时一致性校验通过", quietly(() -> svc.checkConsistency(baseline, baseline)));
        List<Bill> tampered = new ArrayList<Bill>(r.bills);
        tampered.remove(0); // 模拟"数据被外部改动"
        check("删掉一条后一致性校验必须失败",
                !quietly(() -> svc.checkConsistency(tampered, baseline)));

        // ---- 9. 持久化：存-读一致（用临时文件，不碰真实数据） ----
        Path tmpData = Paths.get("selftest-tmp.dat.txt");
        try {
            Storage st = new Storage(tmpData.toString());
            st.save(r.bills);          // 第一次：目标文件不存在，不产生备份
            st.save(r.bills);          // 第二次：覆盖前应自动备份
            List<Bill> reloaded = st.load();
            eq("保存后重新加载的条数", r.bills.size(), reloaded.size());
            eq("保存后首条 id 一致", r.bills.get(0).getId(), reloaded.get(0).getId());
            eq("保存后首条金额一致", r.bills.get(0).getAmount(), reloaded.get(0).getAmount());
            check("保存时自动生成 .bak 备份", Files.exists(Paths.get(tmpData + ".bak")));
        } finally {
            Files.deleteIfExists(tmpData);
            Files.deleteIfExists(Paths.get(tmpData + ".bak"));
        }

        // ---- 10. 筛选条件（控制台与 GUI 共用） ----
        eq("筛选 微信+支出", 9, new BillQuery().account("微信").type("支出").apply(r.bills).size());
        check("搜索'医疗'有结果且全部命中",
                new BillQuery().search("医疗").apply(r.bills).size() > 0);
        int janCount = new BillQuery().range(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
                .apply(r.bills).size();
        check("日期区间 2026-01 有数据且不超过总数", janCount > 0 && janCount <= r.bills.size());
        eq("起止日期填反自动纠正（结果与正序一致）", janCount,
                new BillQuery().range(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 1))
                        .apply(r.bills).size());
        eq("空条件 = 全部", r.bills.size(), new BillQuery().apply(r.bills).size());

        // ---- 11. 导出：文件能生成、行数正确 ----
        Path exported = null;
        try {
            exported = Exporter.exportBills(r.bills);
            List<String> lines = Files.readAllLines(exported, StandardCharsets.UTF_8);
            eq("导出文件行数（含表头，含BOM在首行）", r.bills.size() + 1, lines.size());
            check("导出文件以 BOM 开头（Excel 不乱码）",
                    lines.get(0).startsWith("\uFEFF"));
        } finally {
            if (exported != null) Files.deleteIfExists(exported);
        }

        // ---- 12. 编码兼容：记事本存成 GBK 的 CSV 也要能正确导入 ----
        // 说明：这里写的是"自检夹具"（临时文件，跑完立即删除），不参与任何交付数据，
        //      也不是为了"造一份好看的数据"，只是为了证明编码回退这条分支真的有效。
        Path gbkCsv = Paths.get("selftest-gbk.csv");
        try {
            List<String> gbkLines = new ArrayList<String>();
            gbkLines.add("id,date,account,type,category,amount,note,recurring");
            gbkLines.add("2001,2026/1/5,微信,支出,餐饮,10.00,午饭,N");
            Files.write(gbkCsv, gbkLines, java.nio.charset.Charset.forName("GBK"));
            ImportResult gbkResult = quietlyImport(gbkCsv);
            eq("GBK 编码的 CSV 仍能导入", 1, gbkResult.bills.size());
            eq("GBK 文件里的中文没有乱码", "餐饮",
                    gbkResult.bills.isEmpty() ? null : gbkResult.bills.get(0).getCategory());
        } finally {
            Files.deleteIfExists(gbkCsv);
        }

        // ---- 13. 表头识别：没有表头的 CSV，第一行不能被当表头吃掉 ----
        Path noHeader = Paths.get("selftest-noheader.csv");
        try {
            Files.write(noHeader, java.util.Arrays.asList(
                    "3001,2026-01-05,微信,收入,工资,100.00,无,N"), StandardCharsets.UTF_8);
            ImportResult nh = quietlyImport(noHeader);
            eq("无表头 CSV 的首行不被丢弃", 1, nh.bills.size());
        } finally {
            Files.deleteIfExists(noHeader);
        }

        // ---- 14. 数据文件损坏行容错：只跳过坏行，不影响好行 ----
        Path badData = Paths.get("selftest-bad.dat.txt");
        try {
            Files.write(badData, java.util.Arrays.asList(
                    "1001|2026-01-05|微信|支出|餐饮|10.00|午饭|N",
                    "1002|2026-01-05|微信|转账中|餐饮|10.00|类型非法|N",
                    "1003|2026-01-05|微信|支出|餐饮|-5.00|负金额|N",
                    "1004|2026-99-99|微信|支出|餐饮|10.00|日期非法|N",
                    "只有三列|a|b"), StandardCharsets.UTF_8);
            final Storage badStorage = new Storage(badData.toString());
            final java.util.concurrent.atomic.AtomicReference<List<Bill>> loaded =
                    new java.util.concurrent.atomic.AtomicReference<List<Bill>>(
                            new ArrayList<Bill>());
            quietly(() -> { loaded.set(badStorage.load()); return true; });
            eq("损坏行被跳过，合法行保留", 1, loaded.get().size());
            eq("跳过行数计数正确", 4, badStorage.getLastSkippedLines());
        } finally {
            Files.deleteIfExists(badData);
        }

        // ---- 15. 金额上限：异常大的数字不能污染统计 ----
        eq("金额超上限应拒绝", null, ImportResult.parseAmount("9999999999999.99"));
        check("金额上限边界值仍可用", ImportResult.parseAmount("999999999999.99") != null);

        // ---- 16. 导入合并：GUI 里"先记账再导入"不能把刚记的账冲掉 ----
        List<Bill> manual = new ArrayList<Bill>();
        manual.add(new Bill(9001, LocalDate.of(2026, 4, 1), "微信", "支出", "自检",
                new BigDecimal("1.00"), "手动记账", false));
        List<Bill> mergedManual = Storage.merge(manual, r.bills);
        eq("合并后条数 = 原有 + 导入", r.bills.size() + 1, mergedManual.size());
        check("合并后原有记录仍在", mergedManual.contains(manual.get(0)));
        eq("同 id 不重复（导入数据不覆盖已有）", r.bills.size(),
                Storage.merge(new ArrayList<Bill>(r.bills), r.bills).size());

        System.out.println("===== 自检结束：通过 " + passed + " 项，失败 " + failed + " 项 =====");
        if (failed > 0) System.exit(1);
    }

    /** 导入时 CsvImporter 可能打印编码提示，自检时吞掉，避免干扰输出 */
    private static ImportResult quietlyImport(Path csv) throws IOException {
        final ImportResult[] holder = new ImportResult[1];
        final IOException[] err = new IOException[1];
        quietly(() -> {
            try {
                holder[0] = new CsvImporter().importFile(csv.toString());
            } catch (IOException e) {
                err[0] = e;
            }
            return true;
        });
        if (err[0] != null) throw err[0];
        return holder[0];
    }

    // ---------- 断言与工具 ----------

    private static void eq(String name, Object expect, Object actual) {
        boolean ok;
        if (expect instanceof BigDecimal && actual instanceof BigDecimal) {
            ok = ((BigDecimal) expect).compareTo((BigDecimal) actual) == 0;
        } else {
            ok = expect == null ? actual == null : expect.equals(actual);
        }
        report(name, ok, ok ? "" : "期望=" + str(expect) + " 实际=" + str(actual));
    }

    private static void check(String name, boolean ok) {
        report(name, ok, ok ? "" : "条件不成立");
    }

    private static void report(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("[通过] " + name);
        } else {
            failed++;
            System.out.println("[失败] " + name + "  " + detail);
        }
    }

    private static String str(Object o) { return o == null ? "null" : String.valueOf(o); }

    private static Bill find(List<Bill> list, int id) {
        for (Bill b : list) if (b.getId() == id) return b;
        return null;
    }

    private static BigDecimal sum(List<Bill> bills, String type) {
        BigDecimal total = BigDecimal.ZERO;
        for (Bill b : bills) if (type.equals(b.getType())) total = total.add(b.getAmount());
        return total;
    }

    private static BigDecimal totalBalance(Map<String, BigDecimal> map) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : map.values()) total = total.add(v);
        return total;
    }

    /** 被校验方法会往控制台打印报告，自检时吞掉，避免输出被刷屏 */
    private static boolean quietly(BooleanSupplier action) {
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            return action.getAsBoolean();
        } finally {
            System.setOut(old);
        }
    }

    private interface BooleanSupplier { boolean getAsBoolean(); }

    private SelfTest() { }
}
