package com.luoke.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import com.luoke.app.config.UiConfig;
import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.MaterialCollectionEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.utils.ResourceUtils;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@NotThreadSafe
public class ResourceCounterPanel extends VBox {

    private static volatile ResourceCounterPanel instance;

    private static final double MAX_PANEL_HEIGHT = 400;
    private static final int ICON_SIZE = 24;
    private final FlowPane rowsContainer;
    private final Map<String, Image> iconCache = new HashMap<>();

    private ResourceCounterPanel() {
        super(0);
        setPadding(new Insets(12));
        setPrefWidth(UiConfig.RESOURCE_COUNTER_WIDTH);
        setMaxHeight(MAX_PANEL_HEIGHT);

        setStyle("-fx-background-color: -color-bg-default; " +
                "-fx-background-radius: 10; " +
                "-fx-border-color: -color-border-muted; " +
                "-fx-border-radius: 10;");
        setOpacity(UiConfig.RESOURCE_COUNTER_OPACITY);

        Label titleLabel = new Label("采集统计");
        titleLabel.getStyleClass().add(Styles.TEXT_BOLD);
        titleLabel.setStyle("-fx-text-fill: -color-fg-default;");
        titleLabel.setPadding(new Insets(0, 0, 4, 0));

        rowsContainer = new FlowPane(8, 6);
        rowsContainer.setPrefWrapLength(UiConfig.RESOURCE_COUNTER_WIDTH - 30);
        ScrollPane scrollPane = new ScrollPane(rowsContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        getChildren().addAll(titleLabel, scrollPane);
        VBox.setVgrow(scrollPane, javafx.scene.layout.Priority.ALWAYS);
        rowsContainer.prefWidthProperty().bind(scrollPane.widthProperty().subtract(4));

        setVisible(false);
        setOpacity(0);

        HookRegistry.INSTANCE.register(new AbstractGenericHook<MaterialCollectionEvent>() {
            @Override
            public void onEvent(HookEventType eventType, MaterialCollectionEvent data) {
                refreshData(data.summary(), data.backpackTotals());
            }

            @Override
            public Set<HookEventType> supportedEvents() {
                return Set.of(HookEventType.MATERIAL_COLLECTION_UPDATED);
            }
        });
    }

    public static ResourceCounterPanel getInstance() {
        if (instance == null) {
            synchronized (ResourceCounterPanel.class) {
                if (instance == null) {
                    instance = new ResourceCounterPanel();
                }
            }
        }
        return instance;
    }

    public void refreshData(Map<String, Integer> summary, Map<String, Integer> backpackTotals) {
        Platform.runLater(() -> {
            rowsContainer.getChildren().clear();

            if (summary == null || summary.isEmpty()) {
                toggle(false);
                return;
            }

            // TreeMap 按名称字典序排列，面板显示固定顺序
            summary.forEach((name, total) -> {
                // 无对应图标的丢弃该项
                if (!hasIcon(name)) return;

                HBox row = new HBox(8);
                row.setAlignment(Pos.CENTER_LEFT);

                ImageView iconView = new ImageView(loadIcon(name));
                iconView.setFitWidth(ICON_SIZE);
                iconView.setFitHeight(ICON_SIZE);

                int bpTotal = backpackTotals.getOrDefault(name, 0);
                Label countLabel = new Label(total + " / " + bpTotal);
                countLabel.setStyle("-fx-text-fill: -color-accent-emphasis; -fx-font-weight: bold;");

                row.getChildren().addAll(iconView, countLabel);
                rowsContainer.getChildren().add(row);
            });

            if (!isVisible() || getOpacity() < 1.0) {
                toggle(true);
            }
        });
    }

    private Image loadIcon(String name) {
        // 先查缓存
        Image cached = iconCache.get(name);
        if (cached != null) return cached;

        // 尝试加载
        String path = "/source/icon/" + sanitizeName(name) + ".png";
        try (var is = ResourceUtils.getResourceStream(path)) {
            Image img = new Image(is);
            iconCache.put(name, img);
            return img;
        } catch (Exception ignored) {
        }

        // 没找到图标 → 返回 player.png 占位图
        try (var is = ResourceUtils.getResourceStream("/source/icon/player.png")) {
            Image placeholder = new Image(is, ICON_SIZE, ICON_SIZE, true, true);
            iconCache.put(name, placeholder);
            return placeholder;
        } catch (Exception ignored) {
            iconCache.put(name, null);
            return null;
        }
    }

    private static String sanitizeName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "").trim().replace(' ', '_');
        if (s.isEmpty()) s = "unnamed";
        if (s.length() > 120) s = s.substring(0, 120);
        return s;
    }

    private boolean hasIcon(String name) {
        String path = "/source/icon/" + sanitizeName(name) + ".png";
        try (var is = ResourceUtils.getResourceStream(path)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void toggle(boolean show) {
        // 防止动画冲突：如果目标状态已达到，则跳过
        if (show == (isVisible() && getOpacity() > 0.5)) return;

        FadeTransition ft = new FadeTransition(Duration.millis(300), this);
        if (show) {
            setVisible(true);
            ft.setFromValue(getOpacity());
            ft.setToValue(UiConfig.RESOURCE_COUNTER_OPACITY);
        } else {
            ft.setFromValue(getOpacity());
            ft.setToValue(0.0);
            ft.setOnFinished(_ -> setVisible(false));
        }
        ft.play();
    }
}