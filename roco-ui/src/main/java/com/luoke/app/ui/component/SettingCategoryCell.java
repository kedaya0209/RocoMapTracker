package com.luoke.app.ui.component;

import com.luoke.app.ui.service.SvgManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/**
 * 设置分类列表项渲染器 — 带图标和悬停高亮效果。
 */
public class SettingCategoryCell extends ListCell<SettingCategory> {

    private static final String BG_SELECTED = "-fx-background-color: -color-accent-subtle; -fx-background-radius: 6;";
    private static final String BG_DEFAULT = "-fx-background-color: transparent; -fx-background-radius: 6;";
    private static final String BG_HOVER = "-fx-background-color: -color-bg-inset; -fx-background-radius: 6;";

    @Override
    protected void updateItem(SettingCategory item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
            setStyle("-fx-background-color: transparent;");
            return;
        }

        Node icon = SvgManager.createHoverDrawIcon(item.iconSvg(), 18, 1.5, 400);
        Label name = new Label(item.name());
        name.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 13px;");

        HBox row = new HBox(8, icon, name);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(34);
        row.setPadding(new Insets(0, 12, 0, 12));

        updateRowStyle(row, isSelected());

        // 鼠标悬停 (背景高亮 + 图标画线动画)
        setOnMouseEntered(_ -> {
            if (!isSelected()) row.setStyle(BG_HOVER);
            SvgManager.animateHoverDrawIcon(icon, true, 400);
        });
        setOnMouseExited(_ -> {
            if (!isSelected()) row.setStyle(BG_DEFAULT);
            SvgManager.animateHoverDrawIcon(icon, false, 400);
        });

        // 选中状态监听
        selectedProperty().addListener((_, _, now) -> updateRowStyle(row, now));

        StackPane wrapper = new StackPane(row);
        wrapper.setPadding(new Insets(1, 0, 1, 0));
        wrapper.setMouseTransparent(true);
        setPadding(new Insets(0));
        setGraphic(wrapper);
    }

    private void updateRowStyle(HBox row, boolean selected) {
        row.setStyle(selected ? BG_SELECTED : BG_DEFAULT);
    }
}
