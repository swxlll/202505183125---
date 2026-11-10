package com.accountbook;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Properties;

/** 配置文件：学号、水印、数据文件路径均外置，禁止硬编码 */
public class Config {
    private final Properties props;

    /**
     * @param path 配置文件路径（UTF-8 或 GBK 保存都能读，见 {@link TextIO}）
     * @throws IOException 文件不存在或不可读
     */
    public Config(String path) throws IOException {
        this.props = TextIO.readProperties(Paths.get(path));
    }

    public String get(String key) { return props.getProperty(key); }

    public String getStudentId()  { return get("studentId"); }
    public String getWatermark()  { return get("watermark"); }
    /** 默认数据文件 = 学号.csv（学号本身从配置读取，代码中不硬编码任何学号） */
    public String getCsvPath() {
        return get("dataFile", getStudentId() + ".csv");
    }
    public String getStorePath()  { return get("storeFile", "bills.dat.txt"); }

    /** 加分项 9.1：天气城市名从配置文件读取（不在代码中硬编码城市） */
    public String getWeatherCity() { return get("weather.city", ""); }
    /** 是否启用天气展示（默认启用，可配成 false 以便离线演示） */
    public boolean isWeatherEnabled() {
        return !"false".equalsIgnoreCase(get("weather.enabled", "true"));
    }
    /** 加分项 9.1：和风天气专属 API Host（控制台 → 设置 → API Host），可留空 */
    public String getWeatherHost() { return get("weather.host", "").trim(); }
    /** 加分项 9.1：和风天气 API Key（禁止硬编码在源码中，只放配置文件） */
    public String getWeatherKey() { return get("weather.key", "").trim(); }

    public String get(String key, String def) {
        return props.getProperty(key, def);
    }
}
