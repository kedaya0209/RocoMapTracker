package com.luoke.app.utils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jcip.annotations.ThreadSafe;

/**
 * JSON处理工具类
 * 提供全局唯一的ObjectMapper单例用于JSON序列化和反序列化
 */
@ThreadSafe
public class JsonUtils {

    /**
     * 全局唯一的ObjectMapper单例
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 私有构造方法，方法实例化
     */
    private JsonUtils() {
    }

    /**
     * 获取全局唯一的ObjectMapper实例
     *
     * @return 全局唯一的ObjectMapper实例，永远不为null
     */
    public static ObjectMapper getMapper() {
        return OBJECT_MAPPER;
    }
}
