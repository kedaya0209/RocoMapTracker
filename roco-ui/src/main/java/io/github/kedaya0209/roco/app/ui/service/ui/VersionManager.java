package io.github.kedaya0209.roco.app.ui.service.ui;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.ui.service.VersionMode;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * 版本模式管理器 — 管理当前版本状态与切换回调。
 * <p>
 * 饿汉式单例，模式不持久化，每次启动默认标准版。
 */
@NotThreadSafe
@Slf4j
public class VersionManager {

    private static final VersionManager INSTANCE = new VersionManager();

    private volatile VersionMode currentMode = VersionMode.STANDARD;
    private Consumer<VersionMode> onSwitch;

    private VersionManager() {
    }

    public static VersionManager getInstance() {
        return INSTANCE;
    }

    public VersionMode getCurrentMode() {
        return currentMode;
    }

    /**
     * 注册切换回调（在 JavaFX Application 线程中调用）。
     */
    public void setOnSwitch(Consumer<VersionMode> callback) {
        this.onSwitch = callback;
    }

    /**
     * 切换到指定版本。
     */
    public void switchTo(VersionMode mode) {
        if (currentMode == mode) return;
        currentMode = mode;
        log.info("版本切换: {}", mode == VersionMode.ADVANCED ? "高级版" : "标准版");
        if (onSwitch != null) {
            onSwitch.accept(mode);
        }
    }
}
