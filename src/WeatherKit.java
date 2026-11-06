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
 * 本类由黄友鹏老师提供，已封装好全部网络细节，学生不需要、也不允许修改本类，
 * 直接调用 WeatherKit.now("城市名") 并妥善处理 ApiException 即可。
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
 */
public final class WeatherKit {

    /** 教师申请的和风天气 API Key（免费额度，请勿外传、勿写进公开仓库） */
    private static final String KEY = "在此填入你的和风天气APIKey";

    private static final String GEO_API = "https://geoapi.qweather.com/v2/city/lookup";
    private static final String NOW_API = "https://devapi.qweather.com/v7/weather/now";

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
        String id = lookupCityId(city);
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
            case "401": throw new ApiException("API Key 无效或未配置（401），请联系教师");
            case "402": throw new ApiException("接口调用次数超限（402），请稍后再试");
            case "404": throw new ApiException("查询的资源不存在（404）");
            case "429": throw new ApiException("请求过于频繁（429），请稍后再试");
            default:    throw new ApiException("接口返回错误码 " + code + "：" + abbreviate(body));
        }
    }

    private static String get(String url) throws ApiException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        try {
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new ApiException("HTTP 请求失败，状态码 " + resp.statusCode());
            }
            return resp.body();
        } catch (ApiException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new ApiException("网络连接失败（可能断网或被防火墙拦截）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("请求被中断", e);
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
