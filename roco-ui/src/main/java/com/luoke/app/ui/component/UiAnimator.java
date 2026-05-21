package com.luoke.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.config.RenderConfig;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.util.Duration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@NotThreadSafe
@Slf4j
public class UiAnimator {
    @Getter
    private boolean sidebarVisible = false;

    private Node sidebarNode;
    private Node floatNode;

    public void setupSidebarToggle(Button menuBtn, Node sidebar, Node floatContainer) {
        this.sidebarNode = sidebar;
        this.floatNode = floatContainer;

        // 核心修正：确保在界面渲染后，动态计算宽度并初始化位置
        Platform.runLater(() -> {
            // 获取侧边栏渲染后的真实宽度
            double width = sidebar.getLayoutBounds().getWidth();
            if (width <= 0) {
                // 如果是PrefWidth设置的，尝试获取预设宽度
                width = sidebar.prefWidth(-1);
            }

            // 初始状态：将侧边栏向左偏移自身宽度的距离，实现完全隐藏
            sidebar.setTranslateX(-width);
            log.debug("侧边栏初始化完成，动态宽度: {}", width);
        });

        menuBtn.setOnAction(_ -> toggleSidebar());
    }

    private void toggleSidebar() {
        if (sidebarNode == null) return;
        double currentWidth = sidebarNode.getLayoutBounds().getWidth();

        TranslateTransition st = new TranslateTransition(Duration.millis(RenderConfig.SIDEBAR_ANIM_MS), sidebarNode);
        TranslateTransition ft = new TranslateTransition(Duration.millis(RenderConfig.SIDEBAR_ANIM_MS), floatNode);

        double targetX = sidebarVisible ? -currentWidth : 0;
        double floatTargetX = sidebarVisible ? 0 : currentWidth;

        st.setToX(targetX);
        ft.setToX(floatTargetX);
        st.play();
        ft.play();

        sidebarVisible = !sidebarVisible;
    }

    /**
     * 画布点击时收起侧边栏
     */
    public void closeSidebar() {
        if (sidebarVisible) {
            toggleSidebar();
        }
    }
}