package com.luoke.app.ui.component;

import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.util.Duration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UiAnimator {
    @Getter
    private boolean sidebarVisible = false;

    public void setupSidebarToggle(Button menuBtn, Node sidebar, Node floatContainer) {
        // 创建动画对象
        TranslateTransition st = new TranslateTransition(Duration.millis(250), sidebar);
        TranslateTransition ft = new TranslateTransition(Duration.millis(250), floatContainer);

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

        menuBtn.setOnAction(e -> {
            st.stop();
            ft.stop();

            // 动态获取当前宽度，防止界面调整大小后宽度变化
            double currentWidth = sidebar.getLayoutBounds().getWidth();

            // 计算目标位置
            // 关闭状态 -> 目标 X 是 0 (完全显示)
            // 打开状态 -> 目标 X 是 -宽度 (完全隐藏)
            double targetX = sidebarVisible ? -currentWidth : 0;

            // 悬浮容器（如果有的话）随之向右偏移
            double floatTargetX = sidebarVisible ? 0 : currentWidth;

            st.setToX(targetX);
            ft.setToX(floatTargetX);

            st.play();
            ft.play();

            sidebarVisible = !sidebarVisible;
            log.debug("侧边栏状态: {}，目标位置: {}", sidebarVisible ? "打开" : "关闭", targetX);
        });
    }
}