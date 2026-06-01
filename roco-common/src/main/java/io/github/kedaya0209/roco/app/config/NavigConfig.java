package io.github.kedaya0209.roco.app.config;

import net.jcip.annotations.NotThreadSafe;
import java.util.Properties;

/**
 * 导航模式配置持久化
 */
@NotThreadSafe
public final class NavigConfig {

    /** 导航模式总开关 */
    public static boolean NAVIGATION_ENABLED;
    /** 最大偏转角度（度），超过此角度才进行地图偏转 */
    public static double MAX_DEFLECTION_ANGLE = 5.0;
    /** 地图旋转延迟（毫秒），角度变化后延迟指定时间才旋转 */
    public static long ROTATION_DELAY_MS = 300;
    /** 旋转最小间隔（毫秒），两次旋转之间至少间隔此时间 */
    public static long ROTATION_INTERVAL_MS = 1200;
    /** 导航模式窗口默认透明度 */
    public static double NAV_WINDOW_OPACITY = 0.3;
    /** 防抖阈值（度），超过此角度才触发旋转 */
    public static double DEBOUNCE_THRESHOLD = 2.0;
    /** 进入导航模式时自动开启跟随模式 */
    public static boolean AUTO_FOLLOW_MODE = true;

    private NavigConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    public static void load(Properties prop) {
        NAVIGATION_ENABLED = ConfigHelper.getBool(prop, "navigation.enabled", false);
        MAX_DEFLECTION_ANGLE = ConfigHelper.getDouble(prop, "navigation.max.deflection.angle", 5.0);
        ROTATION_DELAY_MS = ConfigHelper.getLong(prop, "navigation.rotation.delay.ms", 300L);
        ROTATION_INTERVAL_MS = ConfigHelper.getLong(prop, "navigation.rotation.interval.ms", 1200L);
        NAV_WINDOW_OPACITY = ConfigHelper.getDouble(prop, "navigation.window.opacity", 0.3);
        DEBOUNCE_THRESHOLD = ConfigHelper.getDouble(prop, "navigation.debounce.threshold", 2.0);
        AUTO_FOLLOW_MODE = ConfigHelper.getBool(prop, "navigation.auto.follow.mode", true);
    }

    public static void save(StringBuilder sb) {
        sb.append("# 导航模式总开关\n");
        sb.append("navigation.enabled=").append(NAVIGATION_ENABLED).append("\n");
        sb.append("# 最大偏转角度（度），超过此角度才进行地图偏转\n");
        sb.append("navigation.max.deflection.angle=").append(MAX_DEFLECTION_ANGLE).append("\n");
        sb.append("# 地图旋转延迟（毫秒）\n");
        sb.append("navigation.rotation.delay.ms=").append(ROTATION_DELAY_MS).append("\n");
        sb.append("# 旋转最小间隔（毫秒）\n");
        sb.append("navigation.rotation.interval.ms=").append(ROTATION_INTERVAL_MS).append("\n");
        sb.append("# 导航模式窗口默认透明度\n");
        sb.append("navigation.window.opacity=").append(NAV_WINDOW_OPACITY).append("\n");
        sb.append("# 防抖阈值（度）\n");
        sb.append("navigation.debounce.threshold=").append(DEBOUNCE_THRESHOLD).append("\n");
        sb.append("# 进入导航模式时自动开启跟随模式\n");
        sb.append("navigation.auto.follow.mode=").append(AUTO_FOLLOW_MODE).append("\n\n");
    }
}
