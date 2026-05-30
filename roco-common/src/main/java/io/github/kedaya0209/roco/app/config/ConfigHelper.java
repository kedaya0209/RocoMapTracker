package io.github.kedaya0209.roco.app.config;

import net.jcip.annotations.ThreadSafe;

import java.util.Arrays;
import java.util.Properties;

/**
 * 配置加载工具方法
 */
@ThreadSafe
public final class ConfigHelper {

    private ConfigHelper() {
        throw new AssertionError("禁止实例化工具类");
    }

    public static String getStr(Properties prop, String key, String def) {
        String val = prop.getProperty(key);
        return (val == null || val.isBlank()) ? def : val.trim();
    }

    public static int getInt(Properties prop, String key, int def) {
        try {
            return Integer.parseInt(prop.getProperty(key).trim());
        } catch (NumberFormatException | NullPointerException e) {
            return def;
        }
    }

    public static long getLong(Properties prop, String key, long def) {
        try {
            return Long.parseLong(prop.getProperty(key).trim());
        } catch (NumberFormatException | NullPointerException e) {
            return def;
        }
    }

    public static double getDouble(Properties prop, String key, double def) {
        try {
            return Double.parseDouble(prop.getProperty(key).trim());
        } catch (NumberFormatException | NullPointerException e) {
            return def;
        }
    }

    public static boolean getBool(Properties prop, String key, boolean def) {
        String val = prop.getProperty(key);
        return val == null ? def : Boolean.parseBoolean(val.trim());
    }

    public static String[] getStrArray(Properties prop, String key) {
        String s = prop.getProperty(key);
        if (s == null || s.isBlank()) return new String[0];
        return Arrays.stream(s.split(",")).map(String::trim).filter(v -> !v.isBlank()).toArray(String[]::new);
    }

    public static int[] getIntArray(Properties prop, String key) {
        String s = prop.getProperty(key);
        if (s == null || s.isBlank()) return new int[0];
        try {
            return Arrays.stream(s.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
        } catch (NumberFormatException e) {
            return new int[0];
        }
    }
}
