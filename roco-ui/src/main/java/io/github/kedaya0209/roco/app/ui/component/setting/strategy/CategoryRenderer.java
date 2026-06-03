package io.github.kedaya0209.roco.app.ui.component.setting.strategy;

import io.github.kedaya0209.roco.app.capture.FullFrameControl;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingCategory;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingConfigManager;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingFieldBuilder;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * 设置面板分类渲染策略。
 * 每个实现负责构建特定分类的右侧面板内容，并在失活时清理资源。
 */
public interface CategoryRenderer {

    Node render(SettingCategory category, ScrollPane fieldScrollPane,
                SettingConfigManager configManager, SettingFieldBuilder fieldBuilder,
                StackPane dialogRoot, Stage ownerStage, FullFrameControl captureService);

    /** 失活时清理资源（预览、全帧模式等）。 */
    default void onHide(FullFrameControl captureService) {}
}
