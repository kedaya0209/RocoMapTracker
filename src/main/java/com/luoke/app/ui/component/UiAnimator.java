package com.luoke.app.ui.component;

import javafx.animation.TranslateTransition;
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
        TranslateTransition st = new TranslateTransition(Duration.millis(250), sidebar);
        TranslateTransition ft = new TranslateTransition(Duration.millis(250), floatContainer);

        menuBtn.setOnAction(e -> {
            st.stop();
            ft.stop();
            double targetX = sidebarVisible ? -220 : 0;
            double floatTargetX = sidebarVisible ? 0 : 220;
            st.setToX(targetX);
            ft.setToX(floatTargetX);
            st.play();
            ft.play();
            sidebarVisible = !sidebarVisible;
            log.debug("侧边栏状态: {}", sidebarVisible ? "打开" : "关闭");
        });
    }
}