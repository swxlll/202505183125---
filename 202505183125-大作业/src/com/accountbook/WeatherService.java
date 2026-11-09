package com.accountbook;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 天气服务：桥接教师提供的 {@code WeatherKit}（加分项 9.1）。
 *
 * <p><b>职责边界</b>：本类<b>不发送任何网络请求</b>——全项目只有
 * {@code WeatherKit.java} 里出现 {@code java.net} / {@code HttpClient}，
 * 学生自己的类里一行网络代码都没有。本类只做四件事：
 * <ol>
 *   <li>把 {@code config.properties} 的天气配置注入系统属性（供 WeatherKit 读取）；</li>
 *   <li>反射调用（教师的类在默认包，命名包不能直接 import 默认包类型）；</li>
 *   <li>把 {@code ApiException} 统一转成一行中文提示，保证程序不崩、记账流程不中断；</li>
 *   <li>10 分钟结果缓存，减少调用次数、避免触发限流。</li>
 * </ol>
 */
public final class WeatherService {

    /** 教师提供的类名（默认包，故不带包前缀） */
    private static final String KIT_CLASS = "WeatherKit";
    /** 缓存有效期：10 分钟 */
    private static final long CACHE_MILLIS = 10 * 60 * 1000L;
    /** 教师类里 Key 的占位符：出现它就说明用户没填 */
    private static final String KEY_PLACEHOLDER = "在此填入你的和风天气APIKey";

    private static Config configRef;
    private static String cachedCity;
    private static String cachedText;
    private static long cachedAt;

    private WeatherService() { }

    /**
     * 注入天气配置（可重复调用，幂等）。
     * 把 {@code weather.host} 拼成两个完整接口地址、把 {@code weather.key} 写进系统属性，
     * WeatherKit 的静态常量从此取值。未填的项不覆盖（保持教师默认值）。
     */
    public static void install(Config config) {
        if (config != null) configRef = config;
        if (configRef == null) return;
        // 允许两种来源：config.properties，或启动参数 -Dqweather.host / -Dqweather.key
        // （-D 优先，便于演示时临时指定而不改文件）
        String host = normalizeHost(System.getProperty("qweather.host", configRef.getWeatherHost()));
        // 显式指定了完整地址（-Dqweather.geo / -Dqweather.now）时以它为准，host 不再覆盖
        if (!host.isEmpty() && System.getProperty("qweather.geo") == null) {
            System.setProperty("qweather.geo", "https://" + host + "/geo/v2/city/lookup");
            System.setProperty("qweather.now", "https://" + host + "/v7/weather/now");
        }
        String key = System.getProperty("qweather.key", configRef.getWeatherKey());
        if (key != null && !key.trim().isEmpty()) {
            System.setProperty("qweather.key", key.trim());
        }
    }

    /**
     * 查询实时天气（优先缓存），<b>永不抛异常</b>。
     *
     * @param city 城市名（来自 config.properties 的 weather.city）
     * @return 成功时形如 "北京 晴 3°C 湿度30% 东南风1级"；失败时为友好的中文提示
     */
    public static String fetch(String city) {
        return fetch(city, false);
    }

    /**
     * 查询实时天气。
     *
     * @param forceRefresh true 表示忽略缓存重新请求（图形界面"刷新天气"按钮用）
     */
    public static String fetch(String city, boolean forceRefresh) {
        install(null); // 幂等：确保 Class.forName 之前系统属性已就位

        if (city == null || city.trim().isEmpty()) {
            return "未配置城市（请检查 config.properties 中的 weather.city）";
        }
        city = city.trim();

        // 配置不全时不发无意义请求：否则要白等 5~8 秒超时，用户体验极差
        String missing = missingConfig();
        if (missing != null) {
            return "未配置" + missing + "｜打开 out/config.properties 填写后重启程序"
                    + "（Host 在控制台「设置 → API Host」，Key 在「项目管理 → 创建凭据」）"
                    + "（不影响记账功能）";
        }

        long now = System.currentTimeMillis();
        if (!forceRefresh && city.equals(cachedCity) && cachedText != null
                && now - cachedAt < CACHE_MILLIS) {
            return cachedText + "（缓存）";
        }

        try {
            Class<?> kit = Class.forName(KIT_CLASS);
            Method method = kit.getMethod("now", String.class);
            Object result = method.invoke(null, city);
            String text = String.valueOf(result);
            cachedCity = city;
            cachedText = text;
            cachedAt = now;
            return text;
        } catch (ClassNotFoundException e) {
            return "未找到 WeatherKit（请把教师提供的 WeatherKit.java 与源码一起编译）（不影响记账功能）";
        } catch (NoSuchMethodException e) {
            return "WeatherKit 版本不匹配（缺少 now(String) 方法）（不影响记账功能）";
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause(); // ApiException 在这里
            String msg = cause == null ? null : cause.getMessage();
            if (msg == null && cause != null) msg = cause.getClass().getSimpleName();
            return hint("查询失败：" + (msg != null ? msg : "未知原因"), msg);
        } catch (Exception e) {
            return hint("查询失败：" + e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 检查配置是否齐全。
     *
     * @return null 表示齐全；否则返回缺什么（用于拼提示语）
     */
    private static String missingConfig() {
        String host = System.getProperty("qweather.geo", "");
        String key = System.getProperty("qweather.key", "");
        boolean noHost = host.trim().isEmpty();
        boolean noKey = key.trim().isEmpty() || KEY_PLACEHOLDER.equals(key.trim());
        if (noHost && noKey) return " API Host 与 Key";
        if (noHost) return " API Host（weather.host）";
        if (noKey) return " API Key（weather.key）";
        return null;
    }

    /** 失败文案统一收口：把状态码翻译成"发生了什么"，并指出修复路径 */
    private static String hint(String prefix, String raw) {
        StringBuilder sb = new StringBuilder(prefix);
        String r = raw == null ? "" : raw;
        if (r.contains("404")) {
            sb.append("｜404：地址已下线，请确认 weather.host 是控制台「设置 → API Host」里的专属域名");
        } else if (r.contains("403")) {
            sb.append("｜403：Host 与 Key 不属于同一账号，或该 Key 无此接口权限");
        } else if (r.contains("401")) {
            sb.append("｜401：Key 无效或复制不全，请到和风天气控制台重新复制");
        } else if (r.contains("402")) {
            sb.append("｜402：免费额度已用完，次日恢复");
        } else if (r.contains("429")) {
            sb.append("｜429：请求过频，稍后自动恢复（已有 10 分钟缓存）");
        } else if (r.contains("网络连接失败")) {
            sb.append("｜请检查网络 / 代理 / 防火墙，或换手机热点重试");
        } else if (r.contains("解压失败")) {
            sb.append("｜响应解压失败，可能是网络中间设备改写了内容");
        }
        return sb.append("（不影响记账功能）").toString();
    }

    /** 清空缓存（想立刻看到最新天气时调用） */
    public static void clearCache() {
        cachedCity = null;
        cachedText = null;
        cachedAt = 0;
    }

    /** 容错：使用者可能把 "https://xxxx.re.qweatherapi.com/" 整段粘进配置，这里统一剥成纯域名 */
    private static String normalizeHost(String h) {
        String s = h == null ? "" : h.trim();
        if (s.isEmpty()) return "";
        int i = s.indexOf("://");
        if (i >= 0) s = s.substring(i + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s.trim();
    }
}
