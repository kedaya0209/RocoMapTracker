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

        // 布局完成后用真实宽度校正初始位置（prefWidth 在未布局时可能不准确）
        Platform.runLater(() -> {
            double width = sidebar.getLayoutBounds().getWidth();
            if (width > 0) {
                sidebar.setTranslateX(-width);
            }
            // width <= 0 说明布局尚未完成，保持 MainUiComposer 设置的 -1000 初始值
        });

        menuBtn.setOnAction(_ -> toggleSidebar());
    }

    private void toggleSidebar() {
        if (sidebarNode == null) return;
        double currentWidth = sidebarNode.getLayoutBounds().getWidth();

        // 如果 Platform.runLater 校正未生效（translateX 远超 -width），先校正起始位置
        if (sidebarNode.getTranslateX() < -currentWidth - 10) {
            sidebarNode.setTranslateX(-currentWidth);
        }

        // 根据实际 translateX 判断当前是否可见，避免首次点击行为错误
        boolean effectivelyVisible = sidebarNode.getTranslateX() >= -1;

        TranslateTransition st = new TranslateTransition(Duration.millis(RenderConfig.SIDEBAR_ANIM_MS), sidebarNode);
        TranslateTransition ft = new TranslateTransition(Duration.millis(RenderConfig.SIDEBAR_ANIM_MS), floatNode);

        double targetX = effectivelyVisible ? -currentWidth : 0;
        double floatTargetX = effectivelyVisible ? 0 : currentWidth;

        st.setToX(targetX);
        ft.setToX(floatTargetX);
        st.play();
        ft.play();

        sidebarVisible = !effectivelyVisible;
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