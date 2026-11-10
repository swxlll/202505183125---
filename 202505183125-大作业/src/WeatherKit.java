import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 加分项工具类：查询实时天气（数据源：和风天气）。
 *
 * 本类由黄友鹏老师提供，已封装好全部网络细节，直接用 WeatherKit.now("城市名")
 * 并妥善处理 ApiException 即可。
 *
 * 调用示例：
 * <pre>
 * try {
 *     String w = WeatherKit.now("北京");   // 例如：北京 晴 3°C 湿度30% 东南风1级
 *     System.out.println(w);
 * } catch (WeatherKit.ApiException e) {
 *     System.out.println("天气查询失败：" + e.getMessage());
 * }
 * </pre>
 *
 * 运行环境：Java 11+，无需任何第三方依赖。
 *
 * <p><b>本项目对教师原版做的两处必要维护</b>（经教师确认允许修改，其余代码未动）：
 * <ol>
 *   <li><b>地址与 Key 改为可由配置注入</b>：和风天气 2025-04 起用“专属 API Host”取代公共域名，
 *       原版内置的 geoapi.qweather.com 已停服（实测 404）、devapi / api 返回 403 Invalid Host。
 *       现改为 {@code System.getProperty(...)} 取值，<b>默认值仍为教师原值</b>，
 *       未注入时行为与原版完全一致；</li>
 *   <li><b>{@link #get(String)} 补 gzip 解压</b>：服务端强制 gzip 压缩，而 Java 11 的
 *       {@code BodyHandlers.ofString} 不解压，会把压缩字节按 UTF-8 硬解成乱码，
 *       导致 {@code checkCode} 找不到 "code" 字段并误报“接口返回格式异常”。</li>
 * </ol>
 *
 * <p>未改动：{@link #now(String)} 的签名与返回格式、URL 拼装方式、超时（5s 连接 / 8s 请求）、
 * 重定向策略、{@link #pick(String, String)} 正则解析、{@link #checkCode(String)} 错误码语义、
 * {@link ApiException} 类型。
 */
public final class WeatherKit {

    /** 教师申请的和风天气 API Key（免费额度，请勿外传、勿写进公开仓库） */
    private static final String KEY = System.getProperty("qweather.key", "在此填入你的和风天气APIKey");

    // 下面两行只改了"取值来源"：默认值与教师原版完全一致，
    // 若外部通过 config.properties 注入专属 API Host，则用注入值覆盖（见 README「9.1 说明」）
    private static final String GEO_API = System.getProperty("qweather.geo",
            "https://geoapi.qweather.com/v2/city/lookup");
    private static final String NOW_API = System.getProperty("qweather.now",
            "https://devapi.qweather.com/v7/weather/now");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 天气接口调用失败时抛出，message 中包含可供排查的原因 */
    public static class ApiException extends Exception {
        public ApiException(String message) { super(message); }
        public ApiException(String message, Throwable cause) { super(message, cause); }
    }

    private WeatherKit() {}

    /**
     * 查询指定城市的实时天气。
     *
     * @param city 城市名，如 "北京"、"上海"
     * @return 天气摘要字符串，如 "北京 晴 3°C 湿度30% 东南风1级"
     * @throws ApiException 网络失败、城市不存在、接口限流或返回异常时抛出
     */
    public static String now(String city) throws ApiException {
        if (city == null || city.trim().isEmpty()) {
            throw new ApiException("城市名不能为空");
        }
        String id = lookupCityId(city.trim());
        String body = get(NOW_API + "?location=" + id + "&key=" + KEY);
        checkCode(body);
        String text     = pick(body, "\"text\"\\s*:\\s*\"([^\"]+)\"");
        String temp     = pick(body, "\"temp\"\\s*:\\s*\"([^\"]+)\"");
        String humidity = pick(body, "\"humidity\"\\s*:\\s*\"([^\"]+)\"");
        String windDir  = pick(body, "\"windDir\"\\s*:\\s*\"([^\"]+)\"");
        String windSc   = pick(body, "\"windScale\"\\s*:\\s*\"([^\"]+)\"");
        if (text == null || temp == null) {
            throw new ApiException("天气数据解析失败，原始返回：" + abbreviate(body));
        }
        return String.format("%s %s %s°C 湿度%s%% %s%s级",
                city, text, temp,
                humidity == null ? "?" : humidity,
                windDir == null ? "" : windDir,
                windSc == null ? "" : windSc);
    }

    /** 城市名 -> 和风 LocationID */
    private static String lookupCityId(String city) throws ApiException {
        String q = URLEncoder.encode(city, StandardCharsets.UTF_8);
        String body = get(GEO_API + "?location=" + q + "&key=" + KEY);
        checkCode(body);
        String id = pick(body, "\"id\"\\s*:\\s*\"(\\d+)\"");
        if (id == null) throw new ApiException("未找到城市：" + city);
        return id;
    }

    private static void checkCode(String body) throws ApiException {
        String code = pick(body, "\"code\"\\s*:\\s*\"([^\"]+)\"");
        if (code == null) throw new ApiException("接口返回格式异常：" + abbreviate(body));
        switch (code) {
            case "200": return;
            case "401": throw new ApiException("API Key 无效或未配置（401），请核对 config.properties 的 weather.key");
            case "402": throw new ApiException("接口调用次数超限（402），请稍后再试");
            case "403": throw new ApiException("无访问权限（403），Host 与 Key 需来自同一个和风天气账号");
            case "404": throw new ApiException("查询的资源不存在（404），请检查城市名");
            case "429": throw new ApiException("请求过于频繁（429），请稍后再试");
            default:    throw new ApiException("接口返回错误码 " + code + "：" + abbreviate(body));
        }
    }

    private static String get(String url) throws ApiException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                // 和风天气的响应默认是 gzip（Java 客户端并不发 Accept-Encoding，服务端照样压缩），
                // 而 Java 11 的 BodyHandlers.ofString 不会解压，只会把 gzip 字节按 UTF-8 解成乱码，
                // 导致下面 checkCode 找不到 "code" 字段。故按字节取回后自己解压（仅补解压，请求逻辑未改）。
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new ApiException("HTTP 请求失败，状态码 " + resp.statusCode());
            }
            byte[] raw = resp.body();
            String enc = resp.headers().firstValue("content-encoding").orElse("").toLowerCase();
            boolean isGzip = enc.contains("gzip")
                    || (raw.length >= 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b);
            if (isGzip) raw = gunzip(raw);
            return new String(raw, StandardCharsets.UTF_8);
        } catch (ApiException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new ApiException("网络连接失败（可能断网或被防火墙拦截）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("请求被中断", e);
        }
    }

    /** gzip 解压：服务端压缩而 Java 11 不解压，缺了这一步拿到的一定是乱码 */
    private static byte[] gunzip(byte[] in) throws ApiException {
        // 初始容量按 4 倍估算并设上限，避免响应体异常大时一次性分配过多内存
        int estimate = (int) Math.min((long) in.length * 4, 1 << 20);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(estimate);
        try (java.util.zip.GZIPInputStream gz =
                     new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(in))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > (8 << 20)) throw new ApiException("响应体过大，已中止解析");
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new ApiException("响应解压失败（gzip），可能是网络中间设备改写了内容", e);
        }
    }

    private static String pick(String body, String regex) {
        Matcher m = Pattern.compile(regex).matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }
}
