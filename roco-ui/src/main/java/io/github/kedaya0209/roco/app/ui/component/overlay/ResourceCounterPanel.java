package io.github.kedaya0209.roco.app.ui.component.overlay;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import io.github.kedaya0209.roco.app.config.UiConfig;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.MaterialCollectionEvent;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
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

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@NotThreadSafe
public class ResourceCounterPanel extends VBox {

    private static volatile ResourceCounterPanel instance;

    private static final double MAX_PANEL_HEIGHT = 400;
    private static final int ICON_SIZE = 24;
    private final FlowPane rowsContainer;
    private final Map<String, Image> iconCache = new HashMap<>();
    /** 最新的待刷新数据（仅保留最新一份） */
    /** 前一次计数值，用于计数动画 */
    private final Map<String, Integer> prevCounts = new HashMap<>();
    private final AtomicReference<MaterialCollectionEvent> pendingData = new AtomicReference<>();
    /** 脏标记：表示有待刷新数据 */
    private final AtomicBoolean dirty = new AtomicBoolean(false);

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

        AppEvents.subscribe(MaterialCollectionEvent.class, data -> {
            pendingData.set(data);
            if (dirty.compareAndSet(false, true)) {
                Platform.runLater(ResourceCounterPanel.this::flushPending);
            }
        });
    }

    /**
     * 批量刷新：取最新数据并重建 UI。
     * 仅在 FX 线程执行，通过 dirty 标记保证同一帧内多次事件只触发一次刷新。
     */
    private void flushPending() {
        dirty.set(false);
        MaterialCollectionEvent event = pendingData.getAndSet(null);
        if (event != null) {
            refreshData(event.summary(), event.backpackTotals());
        }
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
        rowsContainer.getChildren().clear();

        if (summary == null || summary.isEmpty()) {
            toggle(false);
            prevCounts.clear();
            return;
        }

        summary.forEach((name, total) -> {
            if (!hasIcon(name)) return;

            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);

            ImageView iconView = new ImageView(loadIcon(name));
            iconView.setFitWidth(ICON_SIZE);
            iconView.setFitHeight(ICON_SIZE);

            int bpTotal = backpackTotals.getOrDefault(name, 0);
            int prev = prevCounts.getOrDefault(name, 0);  // 默认 0，避免首次 diff=0
            prevCounts.put(name, total);
            Label countLabel = new Label(prev + " / " + bpTotal);
            countLabel.setStyle("-fx-text-fill: -color-accent-emphasis; -fx-font-weight: bold;");

            row.getChildren().addAll(iconView, countLabel);
            rowsContainer.getChildren().add(row);

            animateCountLabel(countLabel, prev, total, bpTotal);
        });

        if (!isVisible() || getOpacity() < 1.0) {
            toggle(true);
        }
    }

    /**
     * Label 文本计数动画 — 从旧值平滑过渡到新值。
     */
    private void animateCountLabel(Label label, int from, int to, int bpTotal) {
        int diff = Math.abs(to - from);
        // 大跳变（差 >= 200）或无变化时直接设值
        if (diff >= 200 || from == to) {
            label.setText(to + " / " + bpTotal);
            return;
        }
        IntegerProperty counter = new SimpleIntegerProperty(from);
        counter.addListener((_, _, val) ->
                label.setText(val.intValue() + " / " + bpTotal));
        int frames = Math.min(diff, 30);
        Timeline tl = new Timeline();
        for (int i = 1; i <= frames; i++) {
            double fraction = (double) i / frames;
            int value = (int) Math.round(from + (to - from) * fraction);
            KeyFrame kf = new KeyFrame(Duration.millis(i * 10.0),
                    _ -> counter.set(value));
            tl.getKeyFrames().add(kf);
        }
        tl.play();
    }

    private Image loadIcon(String name) {
        // 先查缓存
        Image cached = iconCache.get(name);
        if (cached != null) return cached;

        // 尝试加载
        String path = "/source/icon/" + sanitizeName(name) + ".png";
        try (InputStream is = ResourceUtils.getResourceStream(path)) {
            Image img = new Image(is);
            iconCache.put(name, img);
            return img;
        } catch (Exception ignored) {
        }

        // 没找到图标 → 返回 player.png 占位图
        try (InputStream is = ResourceUtils.getResourceStream("/source/icon/player.png")) {
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
        try (InputStream is = ResourceUtils.getResourceStream(path)) {
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