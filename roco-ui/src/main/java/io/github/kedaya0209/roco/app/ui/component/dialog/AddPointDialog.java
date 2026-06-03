package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.context.ResourcePointContext;
import io.github.kedaya0209.roco.app.map.model.ResourcePoint;
import io.github.kedaya0209.roco.app.ui.component.dialog.ConfirmDialog;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.Set;
import java.util.TreeSet;

/**
 * 新增标记点对话框 — 含自动补全 Popup。
 * 从 InteractiveCanvas 拆分，遵循单一职责原则。
 */
@NotThreadSafe
public class AddPointDialog {

    private AddPointDialog() {
    }

    /**
     * 打开新增标记点对话框。
     *
     * @param rootStack 父容器
     * @param logicX    地图逻辑坐标 X
     * @param logicY    地图逻辑坐标 Y
     */
    public static void open(StackPane rootStack,
                            double logicX, double logicY) {
        Set<String> markTypeSet = new TreeSet<>();
        for (ResourcePoint point : ResourcePointContext.getInstance().getAllPoints()) {
            markTypeSet.add(point.getConfig().getMarkTypeName());
        }
        ObservableList<String> allItems = FXCollections.observableArrayList(markTypeSet);

        TextField input = new TextField();
        input.setPromptText("输入关键字筛选或直接输入新名称…");
        input.setPrefWidth(280);

        ListView<String> suggestionList = new ListView<>();
        suggestionList.setPrefHeight(140);
        suggestionList.setMinWidth(280);
        suggestionList.setStyle("-fx-background-color: #2a2a2a; -fx-border-color: #555; -fx-border-radius: 4; -fx-background-radius: 4;");

        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.getContent().add(suggestionList);

        input.textProperty().addListener((_, _, text) -> {
            if (text == null || text.isBlank()) {
                popup.hide();
                return;
            }
            String lower = text.toLowerCase();
            ObservableList<String> matches = FXCollections.observableArrayList();
            for (String item : allItems) {
                if (item.toLowerCase().contains(lower)) {
                    matches.add(item);
                }
            }
            if (matches.isEmpty()) {
                popup.hide();
            } else {
                suggestionList.setItems(matches);
                suggestionList.getSelectionModel().select(0);
                if (!popup.isShowing()) {
                    Point2D anchor = input.localToScreen(0, input.getHeight());
                    popup.show(input, anchor.getX(), anchor.getY());
                }
                suggestionList.setPrefWidth(input.getWidth());
            }
        });

        suggestionList.setOnMouseClicked(_ -> {
            String selected = suggestionList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                input.setText(selected);
                popup.hide();
                input.requestFocus();
                input.positionCaret(input.getLength());
            }
        });

        input.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER -> {
                    if (popup.isShowing()) {
                        String sel = suggestionList.getSelectionModel().getSelectedItem();
                        if (sel != null) {
                            input.setText(sel);
                            popup.hide();
                            input.positionCaret(input.getLength());
                        }
                    }
                }
                case DOWN -> {
                    if (popup.isShowing() && !suggestionList.getItems().isEmpty()) {
                        suggestionList.requestFocus();
                        suggestionList.getSelectionModel().select(0);
                    }
                }
                case ESCAPE -> popup.hide();
            }
        });

        suggestionList.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER -> {
                    String sel = suggestionList.getSelectionModel().getSelectedItem();
                    if (sel != null) {
                        input.setText(sel);
                        popup.hide();
                        input.requestFocus();
                        input.positionCaret(input.getLength());
                    }
                }
                case ESCAPE -> {
                    popup.hide();
                    input.requestFocus();
                }
                case UP -> {
                    if (suggestionList.getSelectionModel().getSelectedIndex() == 0) {
                        input.requestFocus();
                    }
                }
            }
        });

        VBox content = new VBox(10, new Label("选择或输入新的点位名称:"), input);
        content.setAlignment(Pos.CENTER);
        content.setStyle("-fx-padding: 20 10 10 10;");
        ConfirmDialog.showConfirmDialog(rootStack, "新增标记点", content, () -> {
            String selected = input.getText();
            if (selected != null && !selected.isBlank())
                ResourcePointContext.getInstance().savePoint(selected, logicX, logicY);
        }, () -> {
        });
    }
}
