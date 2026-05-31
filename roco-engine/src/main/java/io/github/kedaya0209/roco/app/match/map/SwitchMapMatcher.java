package io.github.kedaya0209.roco.app.match.map;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.config.ConfigPersistence;
import io.github.kedaya0209.roco.app.config.SiftConfig;
import io.github.kedaya0209.roco.app.match.SiftVariant;
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
 *   <li>通过回调通知上层重启 C++ 进程</li>
 * </ul>
 */
@ThreadSafe
@Setter
@Slf4j
public class SwitchMapMatcher {

    private static volatile SwitchMapMatcher instance;

    /**
     * -- SETTER --
     * 设置变体切换回调 (由 roco-ui 注入)
     */
    private volatile SwitchCallback switchCallback;

    private volatile AlgoKindSwitchCallback algoKindCallback;
    private volatile int lastAlgoKind = SiftConfig.ALGO_KIND;

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

        log.info("切换 MapMatcher 变体: {} → {}", SiftConfig.MAP_MATCHAER, type);

        SiftConfig.MAP_MATCHAER = type;
        ConfigPersistence.save();

        SwitchCallback cb = switchCallback;
        if (cb != null) {
            cb.onSwitch(type);
        }
    }

    public void setAlgoKindCallback(AlgoKindSwitchCallback cb) {
        this.algoKindCallback = cb;
        this.lastAlgoKind = SiftConfig.ALGO_KIND;
    }

    /**
     * 运行时切换算法类型（由侧边栏调用）。
     */
    public void switchAlgoKind(String name) {
        log.info("算法类型固定为 SIFT");
    }

    /**
     * 触发算法类型重启（由设置面板 onApply 调用）。
     * 自动跳过未变更的情况，防止每次应用设置时重复重启。
     */
    public void triggerAlgoKindRestart() {
        int currentAlgoKind = SiftConfig.ALGO_KIND;
        if (currentAlgoKind == lastAlgoKind) return;
        lastAlgoKind = currentAlgoKind;
        AlgoKindSwitchCallback cb = algoKindCallback;
        if (cb != null) {
            cb.onAlgoKindSwitch(currentAlgoKind);
        }
    }

    /**
     * 上层注入的回调: 算法类型变更后触发，由 ModernCanvasApp 重启 C++ 进程
     */
    @FunctionalInterface
    public interface AlgoKindSwitchCallback {
        void onAlgoKindSwitch(int newAlgoKind);
    }

    /**
     * 上层注入的回调: 变体名称变更后触发，由 ModernCanvasApp 重启 C++ 进程
     */
    @FunctionalInterface
    public interface SwitchCallback {
        void onSwitch(String newVariant);
    }
}
