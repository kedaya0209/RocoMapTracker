package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * Native 内存清理工具 — 使用 Unsafe 显式释放 DirectByteBuffer。
 * 替代已过时的 System.runFinalization() 方案。
 */
@Slf4j
public final class NativeCleaner {

    private static final Unsafe UNSAFE;

    static {
        Unsafe instance;
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            instance = (Unsafe) f.get(null);
        } catch (Exception e) {
            log.error("无法获取 Unsafe 实例, DirectByteBuffer 将依赖 GC 回收", e);
            instance = null;
        }
        UNSAFE = instance;
    }

    private NativeCleaner() {
    }

    /**
     * 显式释放 DirectByteBuffer 的 native 内存。
     * 此后禁止再访问该 buffer, 否则会抛异常。
     */
    public static void freeDirectBuffer(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) return;
        if (UNSAFE == null) return;
        try {
            UNSAFE.invokeCleaner(buffer);
        } catch (Exception e) {
            log.warn("DirectByteBuffer 清理失败 (可忽略): {}", e.toString());
        }
    }

    /**
     * 安全释放缓冲区, 并触发一次 GC 辅助回收其他未管理内存。
     */
    public static void freeAndGC(ByteBuffer buffer) {
        freeDirectBuffer(buffer);
        System.gc();
    }
}
