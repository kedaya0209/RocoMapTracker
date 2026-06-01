package io.github.kedaya0209.roco.app.ui.component;

import atlantafx.base.controls.ModalPane;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

@NotThreadSafe
@Slf4j
public class UiAnimator {
    @Getter
    private boolean sidebarVisible = false;

    private ModalPane modalPane;
    private Node sidebarContent;

    public void setupSidebarToggle(Button menuBtn, ModalPane modalPane, Node sidebarContent) {
        this.modalPane = modalPane;
        this.sidebarContent = sidebarContent;

        // 配置为左侧抽屉
        modalPane.setAlignment(Pos.CENTER_LEFT);
        modalPane.usePredefinedTransitionFactories(Side.LEFT);

        // 监听显示状态
        modalPane.displayProperty().addListener((_, _, showing) -> {
            sidebarVisible = showing;
        });

        menuBtn.setOnAction(_ -> toggleSidebar());
    }

    public void toggleSidebar() {
        if (modalPane == null) return;
        if (sidebarVisible) {
            modalPane.hide(true);
        } else {
            modalPane.show(sidebarContent);
        }
    }

    public void closeSidebar() {
        if (sidebarVisible) {
            modalPane.hide(true);
        }
    }
}
