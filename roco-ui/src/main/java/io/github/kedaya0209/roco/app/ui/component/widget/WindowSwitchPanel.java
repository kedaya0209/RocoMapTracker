package io.github.kedaya0209.roco.app.ui.component.widget;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.CaptureConfig;
import io.github.kedaya0209.roco.app.platform.PreviewCapture;
import io.github.kedaya0209.roco.app.platform.WindowDescriptor;
import io.github.kedaya0209.roco.app.platform.WindowFinder;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 多游戏窗口选择面板 — 全屏半透明遮罩，磁贴风格展示各游戏窗口实时预览。
 * <p>
 * DCL 单例，以 StackPane 覆盖层形式添加到 rootStack。
 * 预览画面通过 {@link PreviewCapture} 启动 capture.exe 子进程，
 * 利用 WGC 捕获各窗口内容并实时显示（~2 FPS）。
 */
@NotThreadSafe
@Slf4j
public class WindowSwitchPanel extends StackPane {

    private static final int PANEL_WIDTH = 780;
    private static final int PANEL_HEIGHT = 540;
    private static final int TILE_WIDTH = 220;
    private static final int TILE_HEIGHT = 200;
    private static final int PREVIEW_HEIGHT = 140;
    private static final int TILE_GAP = 16;
    private static final int MAX_TILES = 9;
    private static final int PADDING = 24;
    private static final int TILES_PER_ROW = 3;
    private static final int PREVIEW_FPS = 60;

    private static volatile WindowSwitchPanel instance;

    private final List<TileData> tiles = new ArrayList<>();
    private final Map<Long, PreviewCapture> previewCaptures = new HashMap<>();
    private final AtomicInteger selectedIndex = new AtomicInteger(0);

    private List<WindowDescriptor> windows;
    private long activeHwnd;
    private Consumer<Long> onSelected;
    private VBox tileContainer;
    private Label helpLabel;
    private StackPane rootStack;
    private EventHandler<KeyEvent> keyHandler;

    private WindowSwitchPanel() {
        setBackground(new Background(new BackgroundFill(
                Color.rgb(0, 0, 0, 0.55), CornerRadii.EMPTY, Insets.EMPTY)));
        setPickOnBounds(false);

        // 点击遮罩背景 → 关闭
        setOnMouseClicked(e -> {
            if (e.getTarget() == this) {
                hide();
            }
        });

        initContent();
    }

    public static WindowSwitchPanel getInstance() {
        if (instance == null) {
            synchronized (WindowSwitchPanel.class) {
                if (instance == null) {
                    instance = new WindowSwitchPanel();
                }
            }
        }
        return instance;
    }

    /**
     * 显示选择面板。
     *
     * @param rootStack  主界面 StackPane（用于添加覆盖层）
     * @param activeHwnd 当前已连接的游戏窗口 HWND
     * @param onSelected 选择回调，接收选中的 HWND
     */
    public void showPanel(StackPane rootStack, long activeHwnd, Consumer<Long> onSelected) {
        if (this.rootStack != null && this.rootStack.getChildren().contains(this)) return;

        // 枚举所有游戏窗口
        List<Long> hwnds = WindowFinder.findWindowsByKeyword(CaptureConfig.TARGET_WINDOW_NAME);
        log.info("showPanel: findWindowsByKeyword 返回 {} 个 HWND: {}", hwnds.size(), hwnds);
        if (hwnds.isEmpty()) {
            log.info("未找到游戏窗口，不显示选择面板");
            return;
        }

        this.activeHwnd = activeHwnd;
        this.onSelected = onSelected;
        this.windows = new ArrayList<>(hwnds.size());

        for (long hwnd : hwnds) {
            WindowDescriptor desc = WindowFinder.buildWindowDescriptor(hwnd);
            log.info("showPanel: buildWindowDescriptor(0x{}) -> {}", Long.toHexString(hwnd), desc);
            if (desc != null) {
                windows.add(desc);
            }
        }

        log.info("showPanel: 有效窗口 {} 个", windows.size());

        if (windows.isEmpty()) {
            log.info("未找到有效的游戏窗口");
            return;
        }

        // 限制数量
        if (windows.size() > MAX_TILES) {
            windows = windows.subList(0, MAX_TILES);
        }

        // 选中当前活跃窗口
        selectedIndex.set(0);
        for (int i = 0; i < windows.size(); i++) {
            if (windows.get(i).hwnd() == activeHwnd) {
                selectedIndex.set(i);
                break;
            }
        }

        rebuildTiles();
        updateHelpText();

        // 直接添加覆盖层
        this.rootStack = rootStack;
        rootStack.getChildren().add(this);

        // 请求焦点，防止底层按钮收到未消费的键盘事件
        this.requestFocus();

        // 启动 WGC 预览捕获（每个非活跃窗口一个 capture.exe 子进程）
        startPreviewCapture();

        // 注册键盘事件
        keyHandler = this::onKeyPressed;
        rootStack.getScene().addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
    }

    /**
     * 关闭选择面板。
     */
    public void hide() {
        stopPreviewCapture();

        if (keyHandler != null && rootStack != null && rootStack.getScene() != null) {
            rootStack.getScene().removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
            keyHandler = null;
        }

        if (rootStack != null) {
            rootStack.getChildren().remove(this);
            rootStack = null;
        }
    }

    // ===== WGC 预览捕获（capture.exe 子进程） =====

    private void startPreviewCapture() {
        for (WindowDescriptor w : windows) {
            long hwnd = w.hwnd();
            // 跳过最小化窗口（WGC 无法捕获）
            if (w.isMinimized()) continue;

            PreviewCapture pc = new PreviewCapture(hwnd, PREVIEW_FPS, frameData ->
                    handlePreviewFrame(hwnd, frameData));

            if (pc.start()) {
                previewCaptures.put(hwnd, pc);
            } else {
                log.warn("预览捕获启动失败: hwnd=0x{}", Long.toHexString(hwnd));
            }
        }
    }

    private void stopPreviewCapture() {
        for (PreviewCapture pc : previewCaptures.values()) {
            pc.close();
        }
        previewCaptures.clear();
    }

    /**
     * 处理来自 PreviewCapture 的 WGC 帧数据。
     * 在 recv 线程（非 FX 线程）调用。
     */
    private void handlePreviewFrame(long hwnd, PreviewCapture.FrameData frameData) {
        int w = frameData.width();
        int h = frameData.height();
        int stride = frameData.stride();
        byte[] bgra = frameData.bgra();

        if (w <= 0 || h <= 0 || bgra == null) return;

        // 压缩 stride → w*4（非 FX 线程安全）
        if (stride != w * 4) {
            bgra = compactBgra(bgra, w, h, stride);
        }

        // 缩放到磁贴尺寸（非 FX 线程安全）
        final byte[] scaled = scaleDownBGRA(bgra, w, h, TILE_WIDTH, PREVIEW_HEIGHT);

        Platform.runLater(() -> {
            TileData td = findTileByHwnd(hwnd);
            if (td == null) return;

            WritableImage wi = new WritableImage(TILE_WIDTH, PREVIEW_HEIGHT);
            PixelWriter pw = wi.getPixelWriter();
            pw.setPixels(0, 0, TILE_WIDTH, PREVIEW_HEIGHT,
                    PixelFormat.getByteBgraPreInstance(),
                        scaled, 0, TILE_WIDTH * 4);
            td.previewView.setImage(wi);
        });
    }

    private TileData findTileByHwnd(long hwnd) {
        for (TileData td : tiles) {
            if (td.hwnd() == hwnd) return td;
        }
        return null;
    }

    /**
     * 将 stride 对齐的 BGRA 缓冲区压缩为紧凑格式（w*h*4 字节）。
     */
    private static byte[] compactBgra(byte[] bgra, int w, int h, int stride) {
        byte[] compact = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            System.arraycopy(bgra, y * stride, compact, y * w * 4, w * 4);
        }
        return compact;
    }

    /**
     * 最近邻缩放下采样 BGRA 像素数据。
     */
    private static byte[] scaleDownBGRA(byte[] src, int srcW, int srcH,
                                         int dstW, int dstH) {
        byte[] dst = new byte[dstW * dstH * 4];
        for (int dy = 0; dy < dstH; dy++) {
            int sy = dy * srcH / dstH;
            int srcRowOff = sy * srcW;
            int dstRowOff = dy * dstW;
            for (int dx = 0; dx < dstW; dx++) {
                int sx = dx * srcW / dstW;
                int si = (srcRowOff + sx) * 4;
                int di = (dstRowOff + dx) * 4;
                dst[di]     = src[si];
                dst[di + 1] = src[si + 1];
                dst[di + 2] = src[si + 2];
                dst[di + 3] = src[si + 3];
            }
        }
        return dst;
    }

    // ===== 内容初始化 =====

    private void initContent() {
        VBox contentPanel = new VBox(0);
        contentPanel.setBackground(new Background(new BackgroundFill(
                Color.rgb(25, 25, 35, 0.96), new CornerRadii(16), Insets.EMPTY)));
        contentPanel.setBorder(new Border(new BorderStroke(
                Color.rgb(255, 255, 255, 0.1),
                BorderStrokeStyle.SOLID,
                new CornerRadii(16),
                new BorderWidths(1))));
        contentPanel.setPrefSize(PANEL_WIDTH, PANEL_HEIGHT);
        contentPanel.setMaxSize(PANEL_WIDTH, PANEL_HEIGHT);

        // 标题栏
        HBox titleBar = new HBox();
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(16, 20, 8, 24));

        Label titleLabel = new Label("选择要追踪的游戏窗口");
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 15px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label closeBtn = new Label("✕");
        closeBtn.setStyle("-fx-text-fill: rgba(255,255,255,0.5); -fx-font-size: 18px; -fx-cursor: hand;");
        closeBtn.setOnMouseClicked(_ -> hide());
        closeBtn.setPadding(new Insets(0, 4, 0, 4));

        titleBar.getChildren().addAll(titleLabel, spacer, closeBtn);

        // 磁贴容器
        tileContainer = new VBox(TILE_GAP);
        tileContainer.setAlignment(Pos.CENTER);
        tileContainer.setPadding(new Insets(12, PADDING, 12, PADDING));
        tileContainer.setPrefHeight(PANEL_HEIGHT - 100);

        // 底部帮助文字
        helpLabel = new Label();
        helpLabel.setAlignment(Pos.CENTER);
        helpLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.4); -fx-font-size: 12px;");
        helpLabel.setPadding(new Insets(0, 0, 16, 0));
        helpLabel.setMaxWidth(Double.MAX_VALUE);

        contentPanel.getChildren().addAll(titleBar, tileContainer, helpLabel);

        // 圆角裁剪
        Rectangle clip = new Rectangle(PANEL_WIDTH, PANEL_HEIGHT);
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        contentPanel.setClip(clip);

        // 居中放置
        StackPane.setAlignment(contentPanel, Pos.CENTER);
        getChildren().add(contentPanel);
    }

    // ===== 磁贴构建 =====

    private void rebuildTiles() {
        tileContainer.getChildren().clear();
        tiles.clear();

        // 按行分组
        List<List<WindowDescriptor>> rows = new ArrayList<>();
        List<WindowDescriptor> currentRow = new ArrayList<>();
        for (WindowDescriptor w : windows) {
            currentRow.add(w);
            if (currentRow.size() >= TILES_PER_ROW) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            }
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }

        for (int ri = 0; ri < rows.size(); ri++) {
            HBox rowBox = new HBox(TILE_GAP);
            rowBox.setAlignment(Pos.CENTER);
            for (int ci = 0; ci < rows.get(ri).size(); ci++) {
                int globalIdx = ri * TILES_PER_ROW + ci;
                WindowDescriptor w = rows.get(ri).get(ci);
                Node tile = createTile(globalIdx, w);
                rowBox.getChildren().add(tile);
            }
            tileContainer.getChildren().add(rowBox);
        }

        updateSelection();
    }

    private Node createTile(int index, WindowDescriptor desc) {
        boolean isSelected = index == selectedIndex.get();
        boolean isActive = desc.hwnd() == activeHwnd;

        // 预览区域：ImageView + 占位文字（捕获未就绪时可见）
        StackPane preview = new StackPane();
        preview.setPrefSize(TILE_WIDTH, PREVIEW_HEIGHT);
        preview.setMinSize(TILE_WIDTH, PREVIEW_HEIGHT);
        preview.setMaxSize(TILE_WIDTH, PREVIEW_HEIGHT);
        preview.setStyle(
                "-fx-background-color: rgba(0,0,0,0.3); " +
                        "-fx-background-radius: 8; " +
                        "-fx-border-color: rgba(255,255,255,0.08); " +
                        "-fx-border-radius: 8;");

        ImageView previewImage = new ImageView();
        previewImage.setFitWidth(TILE_WIDTH);
        previewImage.setFitHeight(PREVIEW_HEIGHT);
        previewImage.setPreserveRatio(false);

        Label placeholder = new Label(desc.width() + "×" + desc.height());
        placeholder.setStyle("-fx-text-fill: rgba(255,255,255,0.25); -fx-font-size: 13px;");
        if (desc.isMinimized()) {
            placeholder.setText("已最小化");
            placeholder.setMouseTransparent(true);
        }

        preview.getChildren().addAll(previewImage, placeholder);

        // 窗口信息
        Label titleLabel = new Label(formatWindowTitle(index, desc));
        titleLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-font-weight: bold; -fx-font-size: 12px;");

        Label sizeLabel = new Label(desc.width() + "×" + desc.height());
        sizeLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.4); -fx-font-size: 11px;");

        VBox infoBox = new VBox(0);
        infoBox.getChildren().addAll(titleLabel, sizeLabel);

        VBox tile = new VBox(6);
        tile.setPrefSize(TILE_WIDTH, TILE_HEIGHT);
        tile.setMaxSize(TILE_WIDTH, TILE_HEIGHT);
        tile.setAlignment(Pos.TOP_CENTER);
        tile.getChildren().addAll(preview, infoBox);
        tile.setCursor(Cursor.HAND);
        tile.setStyle(buildTileStyle(isSelected));

        // 活跃窗口标记
        if (isActive) {
            Label activeBadge = new Label("当前");
            activeBadge.setStyle("-fx-text-fill: #4ade80; -fx-font-size: 10px; -fx-font-weight: bold;");
            tile.getChildren().add(activeBadge);
        }

        tiles.add(new TileData(tile, previewImage, desc.hwnd()));

        tile.getProperties().put("tile-index", index);
        tile.setOnMouseClicked(_ -> handleSelect(index));

        return tile;
    }

    // ===== 交互 =====

    private void onKeyPressed(KeyEvent e) {
        if (windows == null || windows.isEmpty()) return;

        int idx = selectedIndex.get();
        int cols = Math.min(TILES_PER_ROW, windows.size());
        int rows = (windows.size() + cols - 1) / cols;

        switch (e.getCode()) {
            case ESCAPE -> {
                hide();
                e.consume();
            }
            case ENTER -> {
                handleSelect(idx);
                e.consume();
            }
            case TAB -> {
                int next = e.isShiftDown()
                        ? (idx - 1 + windows.size()) % windows.size()
                        : (idx + 1) % windows.size();
                setSelection(next);
                e.consume();
            }
            case RIGHT -> {
                if (idx + 1 < windows.size()) { setSelection(idx + 1); e.consume(); }
            }
            case LEFT -> {
                if (idx - 1 >= 0) { setSelection(idx - 1); e.consume(); }
            }
            case DOWN -> {
                int next = idx + cols;
                if (next < windows.size()) { setSelection(next); e.consume(); }
            }
            case UP -> {
                int next = idx - cols;
                if (next >= 0) { setSelection(next); e.consume(); }
            }
            default -> {}
        }
    }

    private void setSelection(int index) {
        selectedIndex.set(index);
        updateSelection();
    }

    private void updateSelection() {
        int sel = selectedIndex.get();
        for (int i = 0; i < tiles.size(); i++) {
            tiles.get(i).node.setStyle(buildTileStyle(i == sel));
        }
    }

    private void handleSelect(int index) {
        if (index < 0 || index >= windows.size()) return;

        long selectedHwnd = windows.get(index).hwnd();
        log.info("handleSelect: index={} selectedHwnd=0x{} activeHwnd=0x{} onSelected={}",
                index, Long.toHexString(selectedHwnd), Long.toHexString(activeHwnd), onSelected);

        if (selectedHwnd == activeHwnd) {
            log.info("handleSelect: 选中窗口即当前活跃窗口，仅关闭面板");
            hide();
            return;
        }

        Consumer<Long> cb = onSelected;
        hide();
        if (cb != null) {
            log.info("handleSelect: 执行切换回调 -> switchTarget(0x{})", Long.toHexString(selectedHwnd));
            cb.accept(selectedHwnd);
        } else {
            log.warn("handleSelect: onSelected 回调为空，无法切换");
        }
    }

    // ===== 辅助方法 =====

    private String buildTileStyle(boolean selected) {
        if (selected) {
            return "-fx-background-radius: 10; -fx-background-color: rgba(255,255,255,0.06); " +
                    "-fx-border-color: #60a5fa; -fx-border-width: 2; -fx-border-radius: 10;";
        }
        return "-fx-background-radius: 10; -fx-background-color: rgba(255,255,255,0.06);";
    }

    private void updateHelpText() {
        helpLabel.setText("⇦⇨ 方向键切换  ⏎ 确认  ⎋ 关闭");
    }

    private static String formatWindowTitle(int index, WindowDescriptor desc) {
        String base = "游戏窗口 #" + (index + 1);
        if (desc.isMinimized()) {
            base += " (▶ 已最小化)";
        }
        return base;
    }

    // ===== 内部数据结构 =====

    private record TileData(Node node, ImageView previewView, long hwnd) {}
}
