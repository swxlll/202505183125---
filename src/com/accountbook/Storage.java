package com.accountbook;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 数据持久化：退出时保存为文本文件，启动时加载并与导入数据合并（按 id 去重，保留已有/最新） */
public class Storage {
    private final String path;

    public Storage(String path) { this.path = path; }

    /** 文本格式：id|date|account|type|category|amount|note|recurring */
    public void save(List<Bill> bills) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8)) {
            for (Bill b : bills) {
                w.write(String.join("|",
                        String.valueOf(b.getId()), b.getDate().toString(), b.getAccount(),
                        b.getType(), b.getCategory(), b.getAmount().toPlainString(),
                        b.getNote().replace("|", "/"), b.isRecurring() ? "Y" : "N"));
                w.newLine();
            }
        }
    }

    public List<Bill> load() {
        List<Bill> bills = new ArrayList<>();
        File f = new File(path);
        if (!f.exists()) return bills;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    String[] c = line.split("\\|", -1);
                    if (c.length != 8) continue; // 持久化文件损坏行直接跳过，不崩溃
                    LocalDate d = DateUtil.parse(c[1]);
                    if (d == null) continue;
                    bills.add(new Bill(Integer.parseInt(c[0]), d, c[2], c[3], c[4],
                            new BigDecimal(c[5]), c[6], "Y".equals(c[7])));
                } catch (RuntimeException e) { // id/金额损坏等，跳过该行
                    System.out.println("[警告] 持久化文件有损坏行，已跳过: " + line);
                }
            }
        } catch (IOException e) {
            System.out.println("[警告] 持久化文件读取失败: " + e.getMessage());
        }
        return bills;
    }

    /** 合并策略：持久化数据优先（含手动记账、转账记录），导入数据中 id 不冲突的补入 */
    public static List<Bill> merge(List<Bill> stored, List<Bill> imported) {
        List<Bill> merged = new ArrayList<>(stored);
        for (Bill b : imported) {
            boolean exists = merged.stream().anyMatch(x -> x.getId() == b.getId());
            if (!exists) merged.add(b);
        }
        merged.sort(java.util.Comparator.comparing(Bill::getDate));
        return merged;
    }
}
