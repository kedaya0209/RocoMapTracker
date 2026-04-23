package com.luoke.app.utils;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 全局唯一的 ObjectMapper 单例
 */
public class JsonUtils {
    // 全局唯一单例
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonUtils() {
    }

    public static ObjectMapper getMapper() {
        return OBJECT_MAPPER;
    }
}