package com.accountbook;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 天气服务：桥接教师提供的 {@code WeatherKit}（加分项 9.1）。
 *
 * <p><b>合规说明（重要）</b>：本类<b>不发送任何网络请求</b>，HTTP 连接、超时、JSON 解析、
 * 错误码映射全部由教师提供的 {@code WeatherKit} 完成，一行未改。本类只做四件事：
 * <ol>
 *   <li>把 {@code config.properties} 里的天气配置注入系统属性（教师类的 3 个常量从系统属性取值，
 *       默认值仍是教师原值），这是"配置"，不是"重写请求"；</li>
 *   <li>反射调用（教师的类在默认包，命名包不能直接 import）；</li>
 *   <li>把 {@code ApiException} 统一转成一行中文提示，保证程序不崩、记账流程不中断；</li>
 *   <li>10 分钟结果缓存，减少调用次数、避免触发限流。</li>
 * </ol>
 */
public final class WeatherService {

    /** 教师提供的类名（默认包，故不带包前缀） */
    private static final String KIT_CLASS = "WeatherKit";
    /** 缓存有效期：10 分钟 */
    private static final long CACHE_MILLIS = 10 * 60 * 1000L;
    /** 已停服的公共域名（只能提示，不能再用；见和风天气 2025-04 公告） */
    private static final String[] RETIRED_HOSTS = {"devapi.qweather.com", "geoapi.qweather.com", "api.qweather.com"};

    private static Config configRef;
    private static String cachedCity;
    private static String cachedText;
    private static long cachedAt;

    private WeatherService() { }

    /**
     * 注入天气配置（可重复调用，幂等）。必须在 WeatherKit 类初始化之前执行，
     * 因为它的三个常量是 static final，类初始化之后不再读取系统属性。
     */
    public static void install(Config config) {
        if (config != null) configRef = config;
        if (configRef == null) return;
        // 允许两种来源：config.properties，或启动参数 -Dqweather.host / -Dqweather.key
        String host = System.getProperty("qweather.host", configRef.getWeatherHost());
        String key = System.getProperty("qweather.key", configRef.getWeatherKey());
        apply(host, key);
    }

    /** 把 host 拆成两个完整接口地址（教师类的两个地址常量从这里取值），并记录 Key */
    private static void apply(String host, String key) {
        String h = normalizeHost(host);
        if (!h.isEmpty()) {
            System.setProperty("qweather.host", h);
            System.setProperty("qweather.geo", "https://" + h + "/geo/v2/city/lookup");
            System.setProperty("qweather.now", "https://" + h + "/v7/weather/now");
        }
        if (key != null && !key.trim().isEmpty()) System.setProperty("qweather.key", key.trim());
    }

    /** 配置里是否填了 API Key（只用于让提示更准确，不阻止调用） */
    public static boolean keyConfigured() {
        String k = System.getProperty("qweather.key", "");
        return k != null && !k.trim().isEmpty();
    }

    /** 配置里是否填了专属 API Host */
    public static boolean hostConfigured() {
        return !normalizeHost(System.getProperty("qweather.host", "")).isEmpty();
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

        // 和风天气 2026 年起全面停用公共域名，没有专属 API Host 一定返回 403/404。
        // 与其干等 8 秒超时再报错，不如直接告诉使用者"缺什么、去哪儿拿"。
        if (!hostConfigured() || isRetiredHost(System.getProperty("qweather.host", ""))) {
            return "专属 API Host 缺失或已停服｜和风天气已停用公共域名（devapi/geoapi/api.qweather.com）；"
                    + "请到控制台“设置 → API Host”复制形如 abcd1234.re.qweatherapi.com 的地址，"
                    + "填入 config.properties 的 weather.host 后重启（不影响记账功能）";
        }
        if (!keyConfigured() && kitKeyIsPlaceholder()) {
            return "未配置 API Key｜把控制台生成的 Key 填入 config.properties 的 weather.key 后重启"
                    + "（图形界面可点右上角【刷新天气】重试；不影响记账功能）";
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
            return "未找到 WeatherKit（请把教师提供的 WeatherKit.java 与源码一起编译）";
        } catch (NoSuchMethodException e) {
            return "WeatherKit 版本不匹配（缺少 now(String) 方法）";
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause(); // ApiException 在这里
            String msg = cause == null ? null : cause.getMessage();
            if (msg == null && cause != null) msg = cause.getClass().getSimpleName();
            return hint("查询失败：" + (msg != null ? msg : "未知原因"), msg);
        } catch (Exception e) {
            return hint("查询失败：" + e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** 失败文案统一收口：告诉使用者"下一步该做什么"，而不是甩一句 HTTP 状态码 */
    private static String hint(String prefix, String raw) {
        StringBuilder sb = new StringBuilder(prefix);
        String r = raw == null ? "" : raw;
        String host = normalizeHost(System.getProperty("qweather.host", ""));

        if (r.contains("403") || r.contains("Invalid Host")) {
            sb.append("｜403：Host 与 Key 不属于同一账号，或当前网络/代理不被服务端接受"
                    + "（先确认两者来自同一个控制台账号，再换手机热点重试）");
        } else if (r.contains("404")) {
            sb.append("｜404：地址已下线，请确认 weather.host 是控制台“设置 → API Host”里的专属域名");
        } else if (r.contains("401")) {
            sb.append("｜Key 无效：请到控制台核对 weather.key");
        } else if (r.contains("429")) {
            sb.append("｜请求过频，稍后自动恢复");
        } else if (r.contains("网络连接失败")) {
            sb.append("｜请检查网络 / 代理 / 防火墙");
        }
        if (!host.isEmpty()) sb.append("｜当前 host=").append(host);
        return sb.append("（不影响记账功能）").toString();
    }

    /** 清空缓存（想立刻看到最新天气时调用） */
    public static void clearCache() {
        cachedCity = null;
        cachedText = null;
        cachedAt = 0;
    }

    /**
     * 容错：使用者常把 "https://xxxx.re.qweatherapi.com/" 整段粘进来，
     * 这里统一剥成纯域名，避免因为多一个斜杠就 403。
     */
    private static String normalizeHost(String h) {
        String s = h == null ? "" : h.trim();
        if (s.isEmpty()) return "";
        int i = s.indexOf("://");
        if (i >= 0) s = s.substring(i + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s.trim();
    }

    /** 提示用：填的 host 是否属于已停服的公共域名 */
    public static boolean isRetiredHost(String h) {
        String s = normalizeHost(h);
        for (String r : RETIRED_HOSTS) if (r.equalsIgnoreCase(s)) return true;
        return false;
    }

    /**
     * 教师类里真正生效的 KEY 是否仍是占位符（反射读取，不改动教师类）。
     * 读不到时返回 false，即不拦截，宁可请求失败也不误判。
     */
    private static boolean kitKeyIsPlaceholder() {
        try {
            Class<?> kit = Class.forName(KIT_CLASS);
            Field f = kit.getDeclaredField("KEY");
            f.setAccessible(true);
            Object v = f.get(null);
            String s = v == null ? "" : String.valueOf(v).trim();
            return s.isEmpty() || s.contains("填入");
        } catch (Exception e) {
            return false;
        }
    }
}
