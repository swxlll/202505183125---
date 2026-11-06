package com.accountbook;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 加分项 9.2：自制图形界面（Swing）。
 *
 * <p>定位：<b>控制台功能全部保留</b>，GUI 只是同一套业务类的另一个入口——菜单里选 9 打开，
 * 关闭窗口后回到控制台并重新加载数据。GUI 内部直接复用 CsvImporter / AccountService /
 * Storage / WeatherService，没有复制任何业务逻辑，因此两种界面算出来的数字必然一致。</p>
 *
 * <p>可用功能：导入 CSV、记账、转账、保存、一致性校验、账单表格（点表头可排序）、
 * 收支统计、各账户余额、实时天气、学号水印。</p>
 */
public final class AccountBookFrame extends JFrame {

    private static final String[] ACCOUNTS = {"微信", "支付宝", "校园卡", "银行卡"};
    private static final String[] TYPES = {"收入", "支出"};
    private static final String[] COLUMNS =
            {"id", "日期", "账户", "类型", "类别", "金额", "备注", "固定支出"};

    private final Config config;
    private final Storage storage;
    private final List<Bill> data;
    /** 打开窗口时的数据快照，用于"一致性校验" */
    private final List<Bill> baseline;

    private final DefaultTableModel model;
    private final JTable table;
    private final JLabel incomeLabel = new JLabel();
    private final JLabel expenseLabel = new JLabel();
    private final JLabel balanceLabel = new JLabel();
    private final JLabel weatherLabel = new JLabel("实时天气：查询中…");
    private final JLabel statusLabel = new JLabel();

    private AccountBookFrame(Config config, Storage storage, List<Bill> data) {
        super("个人记账本 · 图形界面 · " + config.getStudentId());
        this.config = config;
        this.storage = storage;
        this.data = data;
        this.baseline = new ArrayList<Bill>(data);

        this.model = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        this.table = new JTable(model);
        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setRowSorter(new TableRowSorter<DefaultTableModel>(model));

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(buildTop(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);
        setSize(1150, 700);
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { saveData(); }
            @Override public void windowClosed(WindowEvent e) { closedLatch.countDown(); }
        });

        refreshTable();
        refreshStats();
        loadWeatherAsync();
    }

    // ---------------- 顶部：标题 + 统计 + 天气 ----------------

    private JPanel buildTop() {
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));

        JLabel title = new JLabel("个人记账本（变体 B · 多账户）  " + config.getStudentId()
                + "  ·  " + config.getWatermark());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        top.add(title, BorderLayout.NORTH);

        JPanel stats = new JPanel(new GridLayout(1, 3, 10, 0));
        stats.add(card("收入合计", incomeLabel, new Color(0xC6, 0x28, 0x28)));
        stats.add(card("支出合计", expenseLabel, new Color(0x2E, 0x7D, 0x32)));
        stats.add(card("结余", balanceLabel, new Color(0x15, 0x65, 0xC0)));
        top.add(stats, BorderLayout.CENTER);

        JPanel weatherLine = new JPanel(new BorderLayout());
        weatherLine.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));
        weatherLabel.setFont(weatherLabel.getFont().deriveFont(12f));
        weatherLabel.setForeground(new Color(0x5D, 0x40, 0x37));
        weatherLine.add(weatherLabel, BorderLayout.WEST);
        top.add(weatherLine, BorderLayout.SOUTH);
        return top;
    }

    private JPanel card(String caption, JLabel value, Color color) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xDD, 0xDD, 0xDD)),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        JLabel cap = new JLabel(caption);
        cap.setForeground(Color.GRAY);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 14f));
        value.setForeground(color);
        p.add(cap, BorderLayout.NORTH);
        p.add(value, BorderLayout.CENTER);
        return p;
    }

    // ---------------- 底部：操作按钮 + 账户余额 ----------------

    private JPanel buildBottom() {
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));

        JPanel buttons = new JPanel(new GridLayout(1, 7, 8, 0));
        buttons.add(button("导入 CSV", this::doImport));
        buttons.add(button("记一笔", this::doAdd));
        buttons.add(button("转账", this::doTransfer));
        buttons.add(button("一致性校验", this::doCheck));
        buttons.add(button("重新统计", this::refreshStats));
        buttons.add(button("保存", this::doSave));
        buttons.add(button("关闭", this::dispose));
        bottom.add(buttons, BorderLayout.NORTH);

        statusLabel.setForeground(Color.DARK_GRAY);
        bottom.add(statusLabel, BorderLayout.SOUTH);
        return bottom;
    }

    private JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                showMessage("操作失败：" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            }
        });
        return b;
    }

    // ---------------- 业务动作 ----------------

    private void doImport() {
        try {
            ImportResult r = new CsvImporter().importFile(config.getCsvPath());
            data.clear();
            data.addAll(Storage.merge(new ArrayList<Bill>(), r.bills));
            refreshTable();
            refreshStats();
            showMessage("导入完成\n总行数：" + r.totalLines + "\n丢弃：" + r.discarded()
                    + " 行\n去重删除：" + r.dedupRemoved + " 行\n有效：" + data.size() + " 条");
        } catch (IOException e) {
            showMessage("导入失败：" + e.getMessage() + "\n请确认 " + config.getCsvPath() + " 与本程序在同一目录。");
        }
    }

    private void doAdd() {
        JTextField idField = new JTextField(nextId());
        JTextField dateField = new JTextField(LocalDate.now().toString());
        JComboBox<String> accountBox = new JComboBox<String>(ACCOUNTS);
        JComboBox<String> typeBox = new JComboBox<String>(TYPES);
        JTextField categoryField = new JTextField("餐饮");
        JTextField amountField = new JTextField();
        JTextField noteField = new JTextField();
        JCheckBox recBox = new JCheckBox("固定支出");

        JPanel form = form(
                new String[]{"编号 id", "日期(yyyy-M-d)", "账户", "类型", "类别", "金额", "备注", "固定支出"},
                new java.awt.Component[]{idField, dateField, accountBox, typeBox, categoryField,
                        amountField, noteField, recBox});

        if (!showForm("记一笔", form)) return;

        int id;
        BigDecimal amount;
        LocalDate date;
        try {
            id = Integer.parseInt(idField.getText().trim());
            amount = new BigDecimal(amountField.getText().trim());
            date = LocalDate.parse(dateField.getText().trim());
        } catch (RuntimeException e) {
            showMessage("输入有误：id 需为整数、日期形如 2026-4-1、金额需为数字。");
            return;
        }
        if (amount.signum() < 0) { showMessage("金额不能为负。"); return; }
        if (categoryField.getText().trim().isEmpty()) { showMessage("类别不能为空。"); return; }
        for (Bill b : data) if (b.getId() == id) { showMessage("id " + id + " 已存在，请换一个。"); return; }

        data.add(new Bill(id, date, (String) accountBox.getSelectedItem(),
                (String) typeBox.getSelectedItem(), categoryField.getText().trim(), amount,
                noteField.getText().trim().isEmpty() ? "无" : noteField.getText().trim(),
                recBox.isSelected()));
        refreshTable();
        refreshStats();
        statusLabel.setText("已添加账单 id=" + id + "；实时天气可作为备注参考：" + weatherLabel.getText());
    }

    private void doTransfer() {
        JComboBox<String> fromBox = new JComboBox<String>(ACCOUNTS);
        JComboBox<String> toBox = new JComboBox<String>(ACCOUNTS);
        toBox.setSelectedIndex(1);
        JTextField amountField = new JTextField();
        JTextField dateField = new JTextField(LocalDate.now().toString());

        JPanel form = form(new String[]{"转出账户", "转入账户", "金额", "日期(yyyy-M-d)"},
                new java.awt.Component[]{fromBox, toBox, amountField, dateField});
        if (!showForm("账户转账（生成两笔记录）", form)) return;

        String from = (String) fromBox.getSelectedItem();
        String to = (String) toBox.getSelectedItem();
        if (from.equals(to)) { showMessage("转出与转入不能是同一账户。"); return; }
        BigDecimal amount;
        LocalDate date;
        try {
            amount = new BigDecimal(amountField.getText().trim());
            date = LocalDate.parse(dateField.getText().trim());
        } catch (RuntimeException e) {
            showMessage("金额或日期格式有误。");
            return;
        }
        if (amount.signum() <= 0) { showMessage("转账金额必须大于 0。"); return; }

        int newId = Integer.parseInt(nextId());
        while (containsId(newId) || containsId(newId + 1)) newId++;
        Bill[] pair = AccountService.transfer(newId, date, from, to, amount, "转账");
        data.add(pair[0]);
        data.add(pair[1]);
        refreshTable();
        refreshStats();
        showMessage("转账完成：" + from + " -" + amount.toPlainString() + " → "
                + to + " +" + amount.toPlainString() + "\n生成两笔记录 id=" + pair[0].getId()
                + "、" + pair[1].getId());
    }

    private void doCheck() {
        java.io.PrintStream old = System.out;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        boolean ok;
        try {
            System.setOut(new java.io.PrintStream(buf, true, "UTF-8"));
            ok = new AccountService().checkConsistency(data, baseline);
        } catch (IOException e) {
            ok = false;
        } finally {
            System.setOut(old);
        }
        String report = new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        showMessage("一致性校验\n" + report + (ok ? "结论：全部通过" : "结论：存在不一致！"));
    }

    private void doSave() {
        try {
            storage.save(data);
            statusLabel.setText("已保存到 " + config.getStorePath() + "（" + data.size() + " 条）");
        } catch (IOException e) {
            showMessage("保存失败：" + e.getMessage());
        }
    }

    private void saveData() {
        try {
            storage.save(data);
        } catch (IOException e) {
            showMessage("关闭前保存失败：" + e.getMessage());
        }
    }

    // ---------------- 数据刷新 ----------------

    private void refreshTable() {
        model.setRowCount(0);
        List<Bill> sorted = new ArrayList<Bill>(data);
        sorted.sort(Comparator.comparing(Bill::getDate).thenComparing(Bill::getId));
        for (Bill b : sorted) {
            model.addRow(new Object[]{b.getId(), b.getDate(), b.getAccount(), b.getType(),
                    b.getCategory(), b.getAmount().toPlainString(), b.getNote(),
                    b.isRecurring() ? "是" : "否"});
        }
    }

    private void refreshStats() {
        BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
        for (Bill b : data) {
            if ("收入".equals(b.getType())) income = income.add(b.getAmount());
            else expense = expense.add(b.getAmount());
        }
        incomeLabel.setText(income.toPlainString());
        expenseLabel.setText(expense.toPlainString());
        balanceLabel.setText(income.subtract(expense).toPlainString());

        Map<String, BigDecimal> bal = new AccountService().balances(data);
        StringBuilder sb = new StringBuilder("账户余额：");
        for (Map.Entry<String, BigDecimal> e : bal.entrySet()) {
            sb.append(e.getKey()).append(' ').append(e.getValue().toPlainString()).append("   ");
        }
        sb.append(" | 共 ").append(data.size()).append(" 条账单");
        statusLabel.setText(sb.toString());
    }

    /** 天气查询走后台线程，避免断网时窗口卡住（教师类内部超时 5s/8s） */
    private void loadWeatherAsync() {
        if (!config.isWeatherEnabled()) {
            weatherLabel.setText("未启用");
            return;
        }
        final String city = config.getWeatherCity();
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() { return WeatherService.fetch(city); }
            @Override protected void done() {
                try {
                    weatherLabel.setText("实时天气：" + get());
                    weatherLabel.setToolTipText(weatherLabel.getText());
                } catch (Exception e) {
                    weatherLabel.setText("实时天气：暂不可用（不影响记账）");
                }
            }
        }.execute();
    }

    // ---------------- 小工具 ----------------

    private String nextId() {
        int max = 1000;
        for (Bill b : data) if (b.getId() > max) max = b.getId();
        return String.valueOf(max + 1);
    }

    private boolean containsId(int id) {
        for (Bill b : data) if (b.getId() == id) return true;
        return false;
    }

    private JPanel form(String[] labels, java.awt.Component[] fields) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < labels.length; i++) {
            c.gridx = 0; c.gridy = i; c.weightx = 0;
            p.add(new JLabel(labels[i] + "："), c);
            c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            fields[i].setPreferredSize(new Dimension(240, 26));
            p.add(fields[i], c);
        }
        return p;
    }

    private boolean showForm(String title, JPanel form) {
        JDialog dlg = new JDialog(this, title, true);
        final boolean[] ok = {false};
        JButton cancel = new JButton("取消");
        JButton confirm = new JButton("确定");
        cancel.addActionListener(e -> dlg.dispose());
        confirm.addActionListener(e -> { ok[0] = true; dlg.dispose(); });
        JPanel btns = new JPanel();
        btns.add(confirm);
        btns.add(cancel);
        dlg.setLayout(new BorderLayout());
        dlg.add(form, BorderLayout.CENTER);
        dlg.add(btns, BorderLayout.SOUTH);
        dlg.getRootPane().setDefaultButton(confirm);   // 回车 = 确定
        dlg.getRootPane().registerKeyboardAction(e -> dlg.dispose(),
                javax.swing.KeyStroke.getKeyStroke("ESCAPE"),
                javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW); // ESC = 取消
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
        return ok[0];
    }

    private void showMessage(String msg) {
        JOptionPane.showMessageDialog(this, msg, "提示", JOptionPane.INFORMATION_MESSAGE);
    }

    // ---------------- 启动入口（供控制台菜单调用） ----------------

    /**
     * 打开图形界面并阻塞直到窗口关闭。
     *
     * @return true 表示正常打开并关闭（调用方应重新加载数据）；false 表示无图形环境等原因未能打开
     */
    public static boolean show(final Config config, final Storage storage) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[提示] 当前环境没有图形界面（headless），无法打开窗口；"
                    + "控制台功能仍可正常使用。");
            return false;
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 使用默认外观即可
        }

        final AccountBookFrame[] ref = new AccountBookFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                List<Bill> list = new ArrayList<Bill>(storage.load());
                AccountBookFrame f = new AccountBookFrame(config, storage, list);
                f.setVisible(true);
                ref[0] = f;
            });
        } catch (Exception e) {
            System.out.println("[错误] 图形界面启动失败："
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return false;
        }
        if (ref[0] == null) return false;

        ref[0].awaitClosed(); // 阻塞主线程，直到用户关掉窗口
        return true;
    }

    private final CountDownLatch closedLatch = new CountDownLatch(1);

    private void awaitClosed() {
        try {
            closedLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
