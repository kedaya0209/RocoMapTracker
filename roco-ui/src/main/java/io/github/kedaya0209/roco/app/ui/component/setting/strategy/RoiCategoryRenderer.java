package io.github.kedaya0209.roco.app.ui.component.setting.strategy;

import io.github.kedaya0209.roco.app.capture.FullFrameControl;
import io.github.kedaya0209.roco.app.ui.component.setting.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/** 「匹配」/「OCR」分类：ROI 截帧预览 + 坐标参数，嵌入滚动面板内作为子分类。 */
public class RoiCategoryRenderer implements CategoryRenderer {

    private RoiPreview roiPreview;

    @Override
    public Node render(SettingCategory category, ScrollPane fieldScrollPane,
                       SettingConfigManager configManager, SettingFieldBuilder fieldBuilder,
                       StackPane dialogRoot, Stage ownerStage, FullFrameControl captureService) {
        boolean isMatch = "匹配".equals(category.name());
        int roiIdx = isMatch ? 0 : 1;
        String prefix = isMatch ? "ROI_MAP_" : "ROI_OCR_";
        Color accent = isMatch ? Color.rgb(0, 160, 255, 0.8) : Color.rgb(0, 200, 80, 0.8);

        roiPreview = new RoiPreview(roiIdx, "预览", accent);
        roiPreview.setOwnerStage(ownerStage);
        roiPreview.start();
        roiPreview.setOnRoiChanged(() -> configManager.syncRoiControls(prefix));
        roiPreview.setFullFrameMode(true, prefix);
        if (captureService != null) {
            captureService.setFullFrameMode(true);
        }

        // ====== 将 ROI 预览 + 坐标参数嵌入滚动面板顶部作为子分类 ======

        VBox content = (VBox) fieldScrollPane.getContent();

        // 子分类标题
        Label sectionTitle = new Label("ROI 区域");
        sectionTitle.setStyle("-fx-text-fill: -color-fg-default; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 0 4 0;");

        // ROI 坐标参数面板（右侧）
        VBox roiParamPanel = buildRoiParamPanel(category, prefix, fieldBuilder, configManager);

        // ROI 预览 + 坐标左右两栏
        HBox topRow = new HBox(12);
        HBox.setHgrow(roiPreview.getNode(), Priority.ALWAYS);
        topRow.getChildren().addAll(roiPreview.getNode(), roiParamPanel);

        VBox roiSection = new VBox(6);
        roiSection.getChildren().addAll(sectionTitle, topRow);

        content.getChildren().addFirst(roiSection);
        return fieldScrollPane;
    }

    private static VBox buildRoiParamPanel(SettingCategory category, String prefix,
                                            SettingFieldBuilder fieldBuilder,
                                            SettingConfigManager configManager) {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(8, 10, 8, 10));
        panel.setPrefWidth(260);
        panel.setMinWidth(200);
        panel.setStyle("-fx-background-color: -color-bg-inset; -fx-background-radius: 6; " +
                "-fx-border-color: -color-border-muted; -fx-border-radius: 6; -fx-border-width: 0.5;");

        Label title = new Label("ROI 坐标 (万分比)");
        title.setStyle("-fx-text-fill: -color-fg-default; -fx-font-weight: bold; -fx-font-size: 12px;");
        panel.getChildren().add(title);

        for (SettingDef def : category.fields()) {
            if (!def.key().startsWith(prefix)) continue;
            Node ctrl = fieldBuilder.buildControl(def);
            if (ctrl instanceof Control c) {
                configManager.registerControl(def.key(), c);
                if (c instanceof Spinner<?> sp) {
                    sp.getValueFactory().valueProperty().addListener((_, _, newVal) -> {
                        if (newVal != null) configManager.writeField(def.key(), newVal);
                    });
                    sp.getEditor().textProperty().addListener((_, _, _) -> {
                        try {
                            configManager.writeField(def.key(),
                                    Integer.parseInt(sp.getEditor().getText()));
                        } catch (NumberFormatException ignored) {
                        }
                    });
                }
            }
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            Label l = new Label(def.label());
            l.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 12px;");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row.getChildren().addAll(l, spacer, ctrl);
            panel.getChildren().add(row);
        }
        return panel;
    }

    @Override
    public void onHide(FullFrameControl captureService) {
        if (roiPreview != null) {
            roiPreview.stop();
            roiPreview = null;
        }
        if (captureService != null) {
            captureService.setFullFrameMode(false);
        }
    }
}
