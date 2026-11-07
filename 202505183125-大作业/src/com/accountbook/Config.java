package com.accountbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** 配置文件：学号、水印、数据文件路径均外置，禁止硬编码 */
public class Config {
    private final Properties props = new Properties();

    public Config(String path) throws IOException {
        try (FileInputStream in = new FileInputStream(path)) {
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    public String get(String key) { return props.getProperty(key); }

    public String getStudentId()  { return get("studentId"); }
    public String getWatermark()  { return get("watermark"); }
    /** 默认数据文件 = 学号.csv（学号本身从配置读取，代码中不硬编码任何学号） */
    public String getCsvPath() {
        return get("dataFile", getStudentId() + ".csv");
    }
    public String getStorePath()  { return get("storeFile", "bills.dat.txt"); }

    public String get(String key, String def) {
        return props.getProperty(key, def);
    }
}
