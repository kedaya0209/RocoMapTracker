package com.luoke.app.macher.map;

import com.luoke.app.config.ConfigPersistence;
import com.luoke.app.config.SiftConfig;
import com.luoke.app.macher.SiftVariant;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.Set;

/**
 * 匹配算法变体管理器 — 纯配置层，不含 JavaCPP/OpenCV 依赖。
 *
 * <p>架构变更: 旧版 SwitchMapMatcher 直接创建 JavaCPP SiftMapMatcher 实例；
 * 现在所有匹配逻辑移交独立 C++ 进程 (sift_match.exe)，本类仅负责:
 * <ul>
 *   <li>维护可选变体名称列表 (供 UI 下拉菜单)</li>
 *   <li>持久化用户选择到 AppConfig</li>
 *   <li>通过回调通知上层重启 C++ 进程</li>
 * </ul>
 */
@Setter
@Slf4j
public class SwitchMapMatcher {

    private static volatile SwitchMapMatcher instance;

    /**
     * -- SETTER --
     * 设置变体切换回调 (由 roco-ui 注入)
     */
    private volatile SwitchCallback switchCallback;

    private SwitchMapMatcher() {
    }

    public static SwitchMapMatcher getInstance() {
        if (instance == null) {
            synchronized (SwitchMapMatcher.class) {
                if (instance == null) {
                    instance = new SwitchMapMatcher();
                }
            }
        }
        return instance;
    }

    public Set<String> getMatchers() {
        return SiftVariant.getDisplayNames();
    }

    /**
     * 运行时切换匹配算法变体。
     * 保存配置到磁盘并通知上层重启 C++ 进程。
     */
    public void switchMapMatcher(String type) {
        if (Objects.equals(type, SiftConfig.MAP_MATCHAER)) {
            return;
        }

        log.info("Switching MapMatcher variant from {} to {}", SiftConfig.MAP_MATCHAER, type);

        SiftConfig.MAP_MATCHAER = type;
        ConfigPersistence.save();

        SwitchCallback cb = switchCallback;
        if (cb != null) {
            cb.onSwitch(type);
        }
    }

    /**
     * 上层注入的回调: 变体名称变更后触发，由 ModernCanvasApp 重启 C++ 进程
     */
    @FunctionalInterface
    public interface SwitchCallback {
        void onSwitch(String newVariant);
    }
}
