package com.luoke.app.ui.component.setting;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import com.luoke.app.ui.util.FxRippleUtil;
import javafx.animation.FadeTransition;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import java.util.Map;

/**
 * 设置控件工厂 — 根据 SettingDef 生成对应的编辑控件。
 * 包含自定义取色器（统一主题弹窗）。
 */
@NotThreadSafe
public class SettingFieldBuilder {

    private final SettingConfigManager configManager;
    private final StackPane overlayRoot;

    public SettingFieldBuilder(SettingConfigManager configManager, StackPane overlayRoot) {
        this.configManager = configManager;
        this.overlayRoot = overlayRoot;
    }

    /**
     * 根据定义构建编辑控件。
     */
    public Node buildControl(SettingDef def) {
        SettingType type = def.type();

        // BUTTON 不需要读取配置值（无 getter），提前返回
        if (type == SettingType.BUTTON) {
            Button btn = new Button(def.label());
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setPrefHeight(36);
            btn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.ACCENT);
            FxRippleUtil.install(btn);
            btn.setOnAction(_ -> {
                if (def.onApply() != null) def.onApply().run();
            });
            return btn;
        }

        // 其余类型需要当前配置值初始化控件
        Object currentValue = configManager.readField(def.key());

        if (type == SettingType.BOOLEAN) {
            CheckBox cb = new CheckBox();
            cb.setSelected(currentValue instanceof Boolean b && b);
            cb.selectedProperty().addListener((_, _, _) -> configManager.markModified());
            return cb;
        }

        if (type == SettingType.INTEGER) {
            int val = currentValue instanceof Number n ? n.intValue() : 0;
            Spinner<Integer> sp = new Spinner<>(0, 999999, val);
            sp.setPrefWidth(120);
            sp.setEditable(true);
            sp.getValueFactory().valueProperty().addListener((_, _, _) -> configManager.markModified());
            return sp;
        }

        if (type == SettingType.LONG) {
            long val = currentValue instanceof Number n ? n.longValue() : 0L;
            Spinner<Long> sp = new Spinner<>(0L, 9999999L, val);
            sp.setPrefWidth(120);
            sp.setEditable(true);
            sp.getValueFactory().valueProperty().addListener((_, _, _) -> configManager.markModified());
            return sp;
        }

        if (type == SettingType.DOUBLE) {
            double val = currentValue instanceof Number n ? n.doubleValue() : 0.0;
            Spinner<Double> sp = new Spinner<>(0.0, 999999.0, val, 0.1);
            sp.setPrefWidth(120);
            sp.setEditable(true);
            sp.getValueFactory().valueProperty().addListener((_, _, _) -> configManager.markModified());
            return sp;
        }

        if (type == SettingType.STRING) {
            if ("HOVER_GLOW_COLOR".equals(def.key())) {
                return buildColorPicker(def);
            }
            // 关于信息仅展示，不使用输入框
            if (def.key().startsWith("ABOUT_")) {
                if ("ABOUT_REPO".equals(def.key())) {
                    String url = currentValue != null ? currentValue.toString() : "";
                    Hyperlink link = new Hyperlink(url);
                    link.setStyle("-fx-font-size: 12px;");
                    link.setOnAction(_ -> {
                        try {
                            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                        } catch (Exception ignored) {
                        }
                    });
                    return link;
                }
                Label label = new Label(currentValue != null ? currentValue.toString() : "");
                label.setWrapText(true);
                label.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
                return label;
            }
            String val = currentValue != null ? currentValue.toString() : "";
            TextField tf = new TextField(val);
            tf.setPrefWidth(200);
            tf.textProperty().addListener((_, _, _) -> configManager.markModified());
            return tf;
        }

        if (type == SettingType.COMBO) {
            String[] options = def.optionsSupplier().get();
            String val = currentValue != null ? currentValue.toString() : "";
            ComboBox<String> cb = new ComboBox<>();
            cb.setItems(FXCollections.observableArrayList(options));
            cb.setValue(val);
            cb.setPrefWidth("DOWNLOAD_SOURCE".equals(def.key()) ? 360 : 160);

            if ("DOWNLOAD_SOURCE".equals(def.key())) {
                Map<String, String> displayMap = Map.of(
                        "github", "GitHub    https://api.github.com/repos/kedaya0209/RocoMapTracker/releases/latest",
                        "jsdelivr", "jsDelivr  https://cdn.jsdelivr.net/gh/kedaya0209/RocoMapTracker@patches/updates/"
                );
                cb.setCellFactory(_ -> new ListCell<>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : displayMap.getOrDefault(item, item));
                    }
                });
                cb.setButtonCell(new ListCell<>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : displayMap.getOrDefault(item, item));
                    }
                });
            }

            cb.valueProperty().addListener((_, _, _) -> configManager.markModified());
            return cb;
        }

        return new Label("未支持类型: " + type);
    }

    // ================================================================
    // 自定义取色器
    // ================================================================

    private Node buildColorPicker(SettingDef def) {
        Object currentValue = configManager.readField(def.key());
        String initColor = currentValue != null ? currentValue.toString() : "#00BFFF";

        TextField hexField = new TextField(initColor);
        hexField.setPrefWidth(90);

        Rectangle swatch = new Rectangle(28, 24);
        swatch.setArcWidth(4);
        swatch.setArcHeight(4);
        swatch.setStroke(Color.gray(0.5));
        swatch.setStrokeWidth(0.5);
        try {
            swatch.setFill(Color.web(initColor));
        } catch (IllegalArgumentException ignored) {
        }

        hexField.textProperty().addListener((_, _, b) -> {
            try {
                swatch.setFill(Color.web(b));
            } catch (IllegalArgumentException ignored) {
            }
            configManager.markModified();
        });

        Button pickBtn = new Button("选择");
        pickBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED);
        FxRippleUtil.install(pickBtn);
        pickBtn.setOnAction(_ -> showColorPickerDialog(hexField, swatch));

        HBox box = new HBox(8, swatch, hexField, pickBtn);
        box.setAlignment(Pos.CENTER_LEFT);

        configManager.registerControl(def.key(), hexField);

        return box;
    }

    private void showColorPickerDialog(TextField hexField, Rectangle swatch) {
        StackPane root = overlayRoot;
        if (root == null) return;

        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");

        VBox dialog = new VBox(15);
        dialog.setPadding(new Insets(25));
        dialog.setMaxWidth(340);
        dialog.setMaxHeight(Region.USE_PREF_SIZE);
        dialog.setStyle(
                "-fx-background-color: -color-bg-default; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-color: -color-border-muted; " +
                        "-fx-border-radius: 12; " +
                        "-fx-border-width: 1.5;"
        );

        Label title = new Label("选择颜色");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        // 预设色板
        String[] presets = {"#FF0000", "#FF4500", "#FF8C00", "#FFD700", "#ADFF2F", "#00FF00",
                "#00CED1", "#00BFFF", "#1E90FF", "#0000FF", "#8A2BE2", "#FF00FF",
                "#FF69B4", "#C0C0C0", "#808080", "#000000", "#FFFFFF", "#F5F5DC"};
        FlowPane presetPane = new FlowPane(6, 6);
        presetPane.setAlignment(Pos.CENTER);
        presetPane.setPrefWidth(300);
        for (String hex : presets) {
            Rectangle tile = new Rectangle(28, 28);
            tile.setArcWidth(4);
            tile.setArcHeight(4);
            tile.setFill(Color.web(hex));
            tile.setStroke(Color.gray(0.4));
            tile.setStrokeWidth(0.5);
            tile.setCursor(Cursor.HAND);
            tile.setOnMouseClicked(_ -> {
                hexField.setText(hex);
                swatch.setFill(Color.web(hex));
                closeDialog(root, mask);
            });
            tile.setOnMouseEntered(_ -> tile.setOpacity(0.7));
            tile.setOnMouseExited(_ -> tile.setOpacity(1.0));
            presetPane.getChildren().add(tile);
        }

        // 自定义颜色区
        Label customLabel = new Label("自定义颜色代码:");
        customLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");

        HBox customRow = new HBox(8);
        customRow.setAlignment(Pos.CENTER);
        TextField customHex = new TextField(hexField.getText());
        customHex.setPrefWidth(140);
        Rectangle customPreview = new Rectangle(28, 24);
        customPreview.setArcWidth(4);
        customPreview.setArcHeight(4);
        customPreview.setStroke(Color.gray(0.5));
        customPreview.setStrokeWidth(0.5);
        try {
            customPreview.setFill(Color.web(customHex.getText()));
        } catch (IllegalArgumentException ignored) {
        }
        customHex.textProperty().addListener((_, _, b) -> {
            try {
                customPreview.setFill(Color.web(b));
            } catch (IllegalArgumentException ignored) {
            }
        });
        customRow.getChildren().addAll(customHex, customPreview);

        // 按钮
        HBox btnBox = new HBox(10);
        btnBox.setAlignment(Pos.CENTER);
        Button okBtn = new Button("确定");
        okBtn.getStyleClass().addAll(Styles.ACCENT);
        FxRippleUtil.install(okBtn);
        okBtn.setOnAction(_ -> {
            String color = customHex.getText().trim();
            if (!color.startsWith("#")) color = "#" + color;
            try {
                Color.web(color);
                hexField.setText(color);
                swatch.setFill(Color.web(color));
            } catch (IllegalArgumentException ignored) {
            }
            closeDialog(root, mask);
        });
        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED);
        FxRippleUtil.install(cancelBtn);
        cancelBtn.setOnAction(_ -> closeDialog(root, mask));
        btnBox.getChildren().addAll(okBtn, cancelBtn);

        dialog.getChildren().addAll(title, presetPane, customLabel, customRow, btnBox);
        mask.getChildren().add(dialog);
        root.getChildren().add(mask);

        FadeTransition ft = new FadeTransition(Duration.millis(200), mask);
        ft.setToValue(1);
        ft.play();
    }

    private void closeDialog(StackPane root, Node mask) {
        FadeTransition ft = new FadeTransition(Duration.millis(150), mask);
        ft.setToValue(0);
        ft.setOnFinished(_ -> root.getChildren().remove(mask));
        ft.play();
    }
}
