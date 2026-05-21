package com.luoke.app.utils;

import net.jcip.annotations.ThreadSafe;
import org.graalvm.nativeimage.ImageInfo;

/**
 * GraalVM Native Image 环境检测工具。
 */
@ThreadSafe
public final class EnvironmentUtil {

    private EnvironmentUtil() {
    }

    /**
     * 判断当前是否运行在 GraalVM Native Image 中。
     */
    public static boolean isNative() {
        return ImageInfo.isExecutable();
    }
}
