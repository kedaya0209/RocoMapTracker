package io.github.kedaya0209.roco.app.ui.component.setting.strategy;

import io.github.kedaya0209.roco.app.capture.FullFrameControl;
import io.github.kedaya0209.roco.app.ui.component.setting.PluginManagementView;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingCategory;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingConfigManager;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingFieldBuilder;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** 「插件管理」分类：PluginManagementView 替换 ScrollPane 内容。 */
public class PluginCategoryRenderer implements CategoryRenderer {

    @Override
    public Node render(SettingCategory category, ScrollPane fieldScrollPane,
                       SettingConfigManager configManager, SettingFieldBuilder fieldBuilder,
                       StackPane dialogRoot, Stage ownerStage, FullFrameControl captureService) {
        PluginManagementView pmv = new PluginManagementView();
        pmv.setDialogRoot(dialogRoot);
        pmv.refresh();
        fieldScrollPane.setContent(pmv.getNode());
        return fieldScrollPane;
    }
}
