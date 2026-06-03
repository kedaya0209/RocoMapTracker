package io.github.kedaya0209.roco.app.ui.component.setting.strategy;

import io.github.kedaya0209.roco.app.capture.FullFrameControl;
import io.github.kedaya0209.roco.app.ui.component.setting.PlayerPreview;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingCategory;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingConfigManager;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingFieldBuilder;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** 「玩家」分类：顶部 PlayerPreview + 下方字段列表。 */
public class PlayerCategoryRenderer implements CategoryRenderer {

    private PlayerPreview playerPreview;

    @Override
    public Node render(SettingCategory category, ScrollPane fieldScrollPane,
                       SettingConfigManager configManager, SettingFieldBuilder fieldBuilder,
                       StackPane dialogRoot, Stage ownerStage, FullFrameControl captureService) {
        playerPreview = new PlayerPreview(configManager);
        playerPreview.start();

        VBox container = new VBox();
        container.getChildren().addAll(playerPreview.getNode(), fieldScrollPane);
        VBox.setVgrow(fieldScrollPane, Priority.ALWAYS);
        return container;
    }

    @Override
    public void onHide(FullFrameControl captureService) {
        if (playerPreview != null) {
            playerPreview.stop();
            playerPreview = null;
        }
    }
}
