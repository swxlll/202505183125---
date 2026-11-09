package com.accountbook;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据持久化：UTF-8 文本文件（id|日期|账户|类型|类别|金额|备注|固定支出）。
 *
 * <p>保存做了三件"防丢数据"的事，不是简单一句 {@code Files.write}：
 * <ol>
 *   <li><b>先写临时文件再原子替换</b>：写到一半断电/异常，原文件仍然完好，
 *       不会出现"账单只剩半截"；</li>
 *   <li><b>覆盖前自动备份</b>：原文件先复制成 {@code .bak}，改坏了还有一份可回退；</li>
 *   <li><b>写完回读校验</b>：重新读一遍数行数，与内存条数不一致就明确报错，
 *       避免"提示保存成功、实际没写全"的假成功。</li>
 * </ol>
 *
 * <p>加载对损坏行容错：列数不对、日期非法、金额非数字都只跳过该行并计数，
 * 不让一个坏字符毁掉整个文件。
 */
public class Storage {

    private final String path;
    /** 最近一次 load() 跳过的损坏行数，供启动时提示 */
    private int lastSkippedLines;

    public Storage(String path) { this.path = path; }

    public String getPath() { return path; }
    public int getLastSkippedLines() { return lastSkippedLines; }

    /** 保存（原子写入 + 备份 + 回读校验） */
    public void save(List<Bill> bills) throws IOException {
        Path target = Paths.get(path).toAbsolutePath();
        Path dir = target.getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");

        try {
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (Bill b : bills) {
                    w.write(String.join("|",
                            String.valueOf(b.getId()), b.getDate().toString(), b.getAccount(),
                            b.getType(), b.getCategory(), b.getAmount().toPlainString(),
                            b.getNote().replace("|", "/"), b.isRecurring() ? "Y" : "N"));
                    w.newLine();
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp); // 写失败：清掉半成品，原文件保持不动
            throw e;
        }

        if (Files.exists(target)) {
            Path bak = target.resolveSibling(target.getFileName().toString() + ".bak");
            Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 少数文件系统（部分网络盘/Windows 跨卷）不支持原子移动，退化为普通替换
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }

        long written;
        try (java.util.stream.Stream<String> lines = Files.lines(target, StandardCharsets.UTF_8)) {
            written = lines.filter(l -> !l.trim().isEmpty()).count();
        }
        if (written != bills.size()) {
            throw new IOException("保存校验失败：应写入 " + bills.size() + " 条，实际 " + written
                    + " 条（原数据已备份为 " + target.getFileName() + ".bak）");
        }
    }

    /** 加载；文件不存在返回空列表（首次运行属正常情况） */
    public List<Bill> load() {
        List<Bill> bills = new ArrayList<Bill>();
        lastSkippedLines = 0;
        Path p = Paths.get(path);
        if (!Files.exists(p)) return bills;

        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.trim().isEmpty() || line.startsWith("#")) continue; // 空行/注释行
                try {
                    String[] c = line.split("\\|", -1);
                    if (c.length != 8) throw new IllegalArgumentException("列数=" + c.length);
                    LocalDate d = DateUtil.parse(c[1]);
                    if (d == null) throw new IllegalArgumentException("日期非法");
                    bills.add(new Bill(Integer.parseInt(c[0]), d, c[2], c[3], c[4],
                            new BigDecimal(c[5]), c[6], "Y".equals(c[7])));
                } catch (RuntimeException e) {
                    lastSkippedLines++;
                    System.out.println("[警告] 数据文件第 " + lineNo + " 行损坏，已跳过（"
                            + e.getMessage() + "）");
                }
            }
        } catch (IOException e) {
            System.out.println("[警告] 数据文件读取失败: " + e.getMessage());
            warnBackup(p);
        }
        if (lastSkippedLines > 0) {
            System.out.println("[提示] 共跳过 " + lastSkippedLines + " 行损坏数据，其余记录已正常加载。");
        }
        return bills;
    }

    /** 读取失败时提示备份文件是否存在（不自动覆盖，交由使用者决定） */
    private void warnBackup(Path p) {
        Path bak = p.resolveSibling(p.getFileName().toString() + ".bak");
        if (Files.exists(bak)) {
            System.out.println("[提示] 检测到备份文件 " + bak.getFileName()
                    + "，如需恢复可将其重命名为 " + p.getFileName());
        }
    }

    /** 合并策略：持久化数据优先（含手动记账、转账记录），导入数据中 id 不冲突的补入 */
    public static List<Bill> merge(List<Bill> stored, List<Bill> imported) {
        List<Bill> merged = new ArrayList<Bill>(stored);
        for (Bill b : imported) {
            boolean exists = false;
            for (Bill x : merged) {
                if (x.getId() == b.getId()) { exists = true; break; }
            }
            if (!exists) merged.add(b);
        }
        merged.sort(java.util.Comparator.comparing(Bill::getDate));
        return merged;
    }
}
