package io.github.kedaya0209.roco.app.ui.component.setting.strategy;

import io.github.kedaya0209.roco.app.capture.FullFrameControl;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingCategory;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingConfigManager;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingFieldBuilder;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** 默认策略：直接返回字段列表 ScrollPane。 */
public class DefaultCategoryRenderer implements CategoryRenderer {

    @Override
    public Node render(SettingCategory category, ScrollPane fieldScrollPane,
                       SettingConfigManager configManager, SettingFieldBuilder fieldBuilder,
                       StackPane dialogRoot, Stage ownerStage, FullFrameControl captureService) {
        return fieldScrollPane;
    }
}
