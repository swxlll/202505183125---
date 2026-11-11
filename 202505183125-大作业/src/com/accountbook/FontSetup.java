package com.accountbook;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

/**
 * 图形界面字体适配。
 *
 * <p>为什么需要这个类：Swing 的默认字体是逻辑字体（Dialog / SansSerif），它能不能显示中文
 * 取决于运行环境的 fontconfig 能不能找到中文字体。<b>精简安装的 Linux、WSL、Docker 里常常
 * 一个中文字体都没有</b>，此时窗口上所有中文都会变成方框（tofu），而同一个程序在控制台里
 * 却完全正常——因为终端用的是自己的字体，跟 Java 无关。
 *
 * <p>处理策略：
 * <ol>
 *   <li>按常见中文字体名依次查找（Windows 雅黑 / macOS 苹方 / Linux Noto、文泉驿、思源）；</li>
 *   <li><b>不只看名字，还用 {@code canDisplay('中')} 实际验证字形是否存在</b>——
 *       名字叫 "Noto Sans CJK" 但缺字形的情况是存在的；</li>
 *   <li>候选都找不到时，遍历系统全部字体族，取第一个真能显示中文的（兜底）；</li>
 *   <li>把选中的字体写入 UIManager 的所有 {@code *.font} 键，<b>保留原有字号与粗细</b>；</li>
 *   <li>一个都找不到时，在控制台打印明确的安装命令，而不是让用户对着一屏方框猜。</li>
 * </ol>
 *
 * <p>也可用启动参数强制指定：{@code java -Dapp.font="Noto Sans CJK SC" -jar accountbook.jar}。
 */
public final class FontSetup {

    /** 常见中文字体，按"好看程度 + 覆盖率"排序；Windows / macOS / 主流 Linux 都覆盖到 */
    private static final String[] CANDIDATES = {
            "Microsoft YaHei", "微软雅黑",          // Windows
            "PingFang SC", "Hiragino Sans GB",      // macOS
            "Noto Sans CJK SC", "Noto Sans SC",     // Ubuntu/Debian fonts-noto-cjk
            "Source Han Sans SC", "Source Han Sans CN", // 思源黑体
            "WenQuanYi Micro Hei", "WenQuanYi Zen Hei", // 文泉驿
            "Droid Sans Fallback",                  // Android / 部分精简系统
            "SimSun", "宋体", "SimHei", "黑体",      // Windows 老字体
            "AR PL UMing CN", "AR PL UKai CN"       // 文鼎
    };

    /** 用于验证字形是否真的存在（只测一个字可能碰巧有，测三个更稳） */
    private static final char[] PROBE = {'中', '账', '额'};

    private static String chosenFamily;

    /** 读取系统字体失败时的原因（多为无法连接图形环境），用于给出针对性提示 */
    private static String envError;

    private FontSetup() {}

    /**
     * 取系统字体族列表。
     *
     * <p>注意：DISPLAY 变量存在但连不上 X 服务器时，{@code getLocalGraphicsEnvironment()}
     * 抛的是 {@link java.awt.AWTError}（Error 而非 Exception），用 catch (Exception) 接不住，
     * 整个程序会直接崩掉。所以这里显式 catch Throwable。
     */
    private static String[] safeFamilies() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        } catch (Throwable t) {
            // 只保留第一次的原因：类初始化失败后 JVM 会缓存 NoClassDefFoundError，
            // 后续调用拿到的错误信息反而不如第一次准确
            if (envError == null) {
                envError = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
            }
            return new String[0];
        }
    }

    /** 已被选中的字体族名，未安装则为 null */
    public static String getChosenFamily() {
        return chosenFamily;
    }

    /**
     * 安装中文字体到 Swing 全局默认。
     *
     * @return 选中的字体族名；找不到任何可用中文字体时返回 null
     */
    public static String install() {
        Font base = pickChineseFont();
        if (base == null) {
            printMissingFontHelp();
            return null;
        }
        int count = applyToUiDefaults(base);
        chosenFamily = base.getFamily();

        // 只有当系统默认字体不能显示中文时才值得提示（否则属于无谓噪音）
        Font uiDefault = UIManager.getFont("Label.font");
        boolean wasBroken = uiDefault == null || !canShowChinese(uiDefault);
        if (wasBroken) {
            System.out.println("[提示] 当前环境的 Java 默认字体不含中文字形，已自动切换为「"
                    + chosenFamily + "」。");
        }
        if (count == 0) {
            System.out.println("[提示] 字体已选中「" + chosenFamily + "」，但未找到可替换的 UI 字体键。");
        }
        return chosenFamily;
    }

    /** 挑选一个能显示中文的字体；找不到返回 null */
    private static Font pickChineseFont() {
        // 0) 允许用 -Dapp.font=xxx 强制指定（现场演示时可手动切换）
        String forced = System.getProperty("app.font", "").trim();
        if (!forced.isEmpty()) {
            Font f = new Font(forced, Font.PLAIN, 14);
            // 指定了但系统没有这个字体时，Java 会退化成 Dialog，这里要识别出来
            if (f.getFamily().equalsIgnoreCase(forced) && canShowChinese(f)) {
                return f;
            }
            System.out.println("[提示] 指定的字体「" + forced + "」不可用或不能显示中文，改为自动选择。");
        }

        String[] available = safeFamilies();
        if (available.length == 0) return null;

        // 1) 按候选表顺序匹配（忽略大小写，兼容 "Noto Sans CJK SC" 这类带空格的族名）
        for (String want : CANDIDATES) {
            for (String have : available) {
                if (have.equalsIgnoreCase(want)) {
                    Font f = new Font(have, Font.PLAIN, 14);
                    if (canShowChinese(f)) return f;
                }
            }
        }

        // 2) 兜底：候选表没有的发行版（如某些国产系统），扫全部字体找第一个能显示中文的
        for (String have : available) {
            Font f = new Font(have, Font.PLAIN, 14);
            if (canShowChinese(f)) {
                System.out.println("[提示] 候选字体均未命中，改用系统字体「" + have + "」。");
                return f;
            }
        }
        return null;
    }

    /** 字体是否真的能显示中文（不是看名字，是看字形） */
    private static boolean canShowChinese(Font f) {
        if (f == null) return false;
        if (f.canDisplayUpTo(new String(PROBE)) != -1) return false; // 有任何一个显示不了就否掉
        // 再确认它不是"用方框代替"的替身字体
        return f.canDisplay(PROBE[0]);
    }

    /**
     * 把字体写进 UIManager 的所有字体键，保留各控件原有字号与粗细
     * （统一改成同一字号会让菜单、表格、状态栏的比例全乱）。
     *
     * @return 实际替换的键数量
     */
    private static int applyToUiDefaults(Font base) {
        // 先收集 key 再修改：边遍历边 put 会抛 ConcurrentModificationException
        List<Object> keys = new ArrayList<Object>();
        for (Enumeration<Object> e = UIManager.getDefaults().keys(); e.hasMoreElements(); ) {
            Object k = e.nextElement();
            if (!(k instanceof String)) continue;
            String s = (String) k;
            if (s.endsWith(".font") || s.endsWith(".acceleratorFont") || "defaultFont".equals(s)) {
                keys.add(k);
            }
        }

        int n = 0;
        for (Object k : keys) {
            Font old = UIManager.getFont(k);
            int style = old != null ? old.getStyle() : Font.PLAIN;
            int size = old != null ? old.getSize() : base.getSize();
            UIManager.put(k, new FontUIResource(new Font(base.getFamily(), style, size)));
            n++;
        }
        return n;
    }

    private static String describeGraphicsEnv() {
        String display = System.getenv("DISPLAY");
        if (GraphicsEnvironment.isHeadless()) return "无图形界面（headless，DISPLAY 未设置）";
        if (envError != null) return "无法连接图形环境（DISPLAY=" + display + "，" + envError + "）";
        return "正常（DISPLAY=" + display + "）";
    }

    /** 缺字体时给出可执行的中文指引，而不是让用户干瞪眼 */
    private static void printMissingFontHelp() {
        System.out.println();
        // 先区分"环境根本连不上"和"环境正常但确实没装中文字体"，两种情况的解决办法完全不同
        if (envError != null) {
            System.out.println("[警告] 读不到系统字体列表：" + envError);
            System.out.println("       常见原因：在 SSH / WSL 里没有可用的图形环境，或 DISPLAY 指向了不存在的 X 服务器。");
            System.out.println("       控制台功能不受影响；要用图形界面，请在有桌面的环境运行，或先修好 DISPLAY。");
            System.out.println();
            return;
        }
        System.out.println("[警告] 当前系统没有可用的中文字体，窗口里的中文会显示成方框（□）。");
        System.out.println("       这不是程序的问题：Java 只能使用系统已安装的字体。");
        System.out.println("       解决办法（任选其一，装完重新打开程序即可）：");
        System.out.println("         Ubuntu / Debian / WSL : sudo apt update && sudo apt install -y fonts-noto-cjk");
        System.out.println("         CentOS / RHEL / 麒麟  : sudo yum install -y google-noto-sans-cjk-fonts");
        System.out.println("         Arch / Manjaro        : sudo pacman -S noto-fonts-cjk");
        System.out.println("         装完刷新字体缓存     : fc-cache -fv");
        System.out.println("       验证是否装好：fc-list :lang=zh | head");
        System.out.println("       本机 Java 能看到哪些字体：java -jar accountbook.jar --fonts");
        System.out.println();
    }

    /** 诊断用：打印 Java 可见的字体情况，便于在陌生环境定位问题 */
    public static void printAvailable() {
        String[] families = safeFamilies();
        System.out.println("===== Java 字体诊断 =====");
        System.out.println("操作系统      : " + System.getProperty("os.name")
                + " " + System.getProperty("os.version"));
        System.out.println("Java 版本     : " + System.getProperty("java.version"));
        System.out.println("图形环境      : " + describeGraphicsEnv());
        System.out.println("可见字体族总数: " + families.length);

        List<String> cjk = new ArrayList<String>();
        for (String f : families) {
            if (canShowChinese(new Font(f, Font.PLAIN, 14))) cjk.add(f);
        }
        System.out.println("能显示中文的  : " + cjk.size() + " 个");
        if (!cjk.isEmpty()) {
            System.out.println("  例如        : " + String.join(" / ",
                    cjk.subList(0, Math.min(6, cjk.size()))));
        }
        System.out.println();
        System.out.println("本次将使用    : " + (pickChineseFont() == null ? "（无，界面会显示方框）"
                : pickChineseFont().getFamily()));
        if (cjk.isEmpty()) {
            System.out.println();
            printMissingFontHelp();
        }
    }
}
