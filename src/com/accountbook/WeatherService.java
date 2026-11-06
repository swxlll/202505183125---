package com.accountbook;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 天气服务：桥接教师提供的 {@code WeatherKit}（加分项 9.1）。
 *
 * <p>为什么用反射调用：教师的 WeatherKit.java 位于<b>默认包</b>（文件里没有 package 声明），
 * 而本项目所有类都在 {@code com.accountbook} 包下。Java 语言规范禁止命名包引用默认包中的类型，
 * 直接 {@code import WeatherKit} 或 {@code WeatherKit.now(...)} 都无法通过编译。
 * 为了<b>不修改、不复制、不重写</b>教师提供的网络代码，这里用反射调用它公开的静态方法
 * {@code now(String)}，既能正常使用，也保持教师源文件字节级不变。</p>
 *
 * <p>另外做了三件工程上的事（都不属于"重写网络代码"）：
 * <ol>
 *   <li>缓存：10 分钟内同一城市复用上次结果，避免频繁调用触发接口限流（429）；</li>
 *   <li>降级：WeatherKit 抛出的 {@code ApiException}（断网/限流/Key 无效）在此统一转成
 *       一行友好文案返回，<b>绝不让异常冒泡导致程序崩溃</b>；</li>
 *   <li>容错：WeatherKit 类缺失或方法签名变化时也不崩，返回可读提示。</li>
 * </ol>
 */
public final class WeatherService {

    /** 教师提供的类名（默认包，故不带包前缀） */
    private static final String KIT_CLASS = "WeatherKit";
    /** 缓存有效期：10 分钟 */
    private static final long CACHE_MILLIS = 10 * 60 * 1000L;

    private static String cachedCity;
    private static String cachedText;
    private static long cachedAt;

    private WeatherService() { }

    /**
     * 查询实时天气，永不抛异常。
     *
     * @param city 城市名（来自 config.properties 的 weather.city）
     * @return 成功时形如 "北京 晴 3°C 湿度30% 东南风1级"；失败时为友好的中文提示
     */
    public static String fetch(String city) {
        if (city == null || city.trim().isEmpty()) {
            return "未配置城市（请检查 config.properties 中的 weather.city）";
        }
        city = city.trim();

        long now = System.currentTimeMillis();
        if (city.equals(cachedCity) && cachedText != null && now - cachedAt < CACHE_MILLIS) {
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
            // 被调方法内部抛出的异常（ApiException 就在这里），取出原始原因
            Throwable cause = e.getCause();
            String msg = cause == null ? null : cause.getMessage();
            if (msg == null && cause != null) msg = cause.getClass().getSimpleName();
            return "查询失败：" + (msg != null ? msg : "未知原因") + "（不影响记账功能）";
        } catch (Exception e) {
            return "查询失败：" + e.getClass().getSimpleName() + "（不影响记账功能）";
        }
    }
}
