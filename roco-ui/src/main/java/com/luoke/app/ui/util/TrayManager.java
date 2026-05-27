package com.luoke.app.ui.util;

import com.luoke.app.config.CaptureConfig;
import com.luoke.app.config.PathConfig;
import com.luoke.app.config.SiftConfig;
import com.luoke.app.ui.service.SvgManager;
import com.luoke.app.utils.FilePathUtil;
import com.luoke.app.utils.ResourceUtils;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 系统托盘管理器 — AWT TrayIcon + 自定义 JavaFX Popup 菜单。
 * <p>
 * 用 AWT TrayIcon 仅显示托盘图标、接收鼠标事件，不设 PopupMenu（避免 AWT
 * {@link Stage} 作为 owner，实现无任务栏条目、无装饰、自动隐藏的右键菜单。
 * Native Image 下 AWT 初始化可能失败，此时降级为普通最小化，不崩溃。
 * </p>
 */
@NotThreadSafe
@Slf4j
public class TrayManager {

    private final Stage primaryStage;
    private boolean initialized;
    private boolean trayAvailable = true;
    private TrayIcon trayIcon;
    private Stage menuStage;
    /** 隐藏 owner Stage：防止菜单 Stage 出现在任务栏 */
    private Stage ownerStage;

    public TrayManager(Stage primaryStage) {
        this.primaryStage = primaryStage;
        Platform.setImplicitExit(false);

        // OS 窗口关闭按钮：托盘可用时最小化到托盘，否则退出
        primaryStage.setOnCloseRequest(e -> {
            if (trayAvailable) {
                e.consume();
                minimizeToTray();
            } else {
                Platform.exit();
            }
        });

        // 隐藏 owner Stage，使菜单不出现任务栏图标
        ownerStage = new Stage();
        ownerStage.initStyle(StageStyle.UTILITY);
        ownerStage.setWidth(1);
        ownerStage.setHeight(1);
        ownerStage.setOpacity(0);
        ownerStage.setX(-10000);
        ownerStage.setY(-10000);
        ownerStage.setScene(new Scene(new Pane()));
        ownerStage.show();
    }

    /**
     * 创建托盘图标（AWT TrayIcon + 含占位项的 PopupMenu）。
     */
    public void init() {
        if (initialized) return;
        initialized = true;
        if (!trayAvailable) return;

        try {
            Image icon = loadTrayImage();
            if (icon == null) {
                log.warn("托盘图标加载失败");
                trayAvailable = false;
                return;
            }

            // 不设 PopupMenu：Windows 仍会投递右键事件到 MouseListener，
            // 且避免 AWT 弹出系统级菜单抢占 Z 序（让 JavaFX Popup 独占）。
            trayIcon = new TrayIcon(icon, CaptureConfig.APP_MAIN_TITLE);
            // 不调用 setImageAutoSize(true)：使用精确物理像素的图标，避免 AWT 二次缩放劣化

            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) { handleClick(e); }

                @Override
                public void mouseReleased(MouseEvent e) { handleClick(e); }
            });
            trayIcon.addActionListener(_ -> showWindow());

            SystemTray.getSystemTray().add(trayIcon);
            log.info("系统托盘已创建（AWT TrayIcon + JavaFX Popup 菜单）");

        } catch (Throwable e) {
            log.warn("创建系统托盘失败: {}", e.getMessage());
            trayAvailable = false;
        }
    }

    /**
     * 最小化窗口至系统托盘。
     */
    public void minimizeToTray() {
        if (!initialized) init();
        if (!trayAvailable) {
            Platform.runLater(() -> primaryStage.setIconified(true));
            return;
        }
        Platform.runLater(() -> {
            if (primaryStage.isShowing()) {
                primaryStage.hide();
                SiftConfig.SIFT_MATCHING_ENABLED = false;
                log.info("窗口最小化至托盘，已暂停匹配");
            }
        });
    }

    /**
     * 销毁托盘图标和菜单。
     */
    public void dispose() {
        if (trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
                trayIcon = null;
                log.info("系统托盘图标已移除");
            } catch (Throwable e) {
                log.warn("移除托盘图标失败", e);
            }
        }
        if (menuStage != null) {
            menuStage.hide();
            menuStage = null;
        }
        if (ownerStage != null) {
            ownerStage.hide();
            ownerStage = null;
        }
    }

    // ---- 鼠标事件处理 ----

    private void handleClick(MouseEvent e) {
        if (e.isPopupTrigger()) {
            int x = e.getXOnScreen();
            int y = e.getYOnScreen();
            Platform.runLater(() -> showMenu(x, y));
        }
    }

    private void showWindow() {
        Platform.runLater(() -> {
            if (!primaryStage.isShowing()) {
                primaryStage.show();
                SiftConfig.SIFT_MATCHING_ENABLED = true;
                log.info("窗口恢复显示，已恢复匹配");
            }
            primaryStage.toFront();
            primaryStage.requestFocus();
        });
    }

    // ---- JavaFX 菜单（Stage 替代 Popup，避免自动位置修正） ----

    private void showMenu(int screenX, int screenY) {
        try {
            // AWT 物理像素 → JavaFX 逻辑像素（除以 DPI 缩放因子）
            double scaleX = Screen.getPrimary().getOutputScaleX();
            double scaleY = Screen.getPrimary().getOutputScaleY();
            Rectangle2D vb = Screen.getPrimary().getVisualBounds();
            int mw = 170;
            int mh = 80;

            double sx = screenX / scaleX;
            double sy = screenY / scaleY;

            // 水平：在图标右侧弹出（菜单左边缘对齐点击位置）
            double x = Math.min(sx, vb.getMaxX() - mw);

            // 垂直：菜单底部对齐光标
            double y = sy - mh;

            if (menuStage == null) {
                menuStage = new Stage();
                menuStage.initStyle(StageStyle.TRANSPARENT);
                menuStage.initOwner(ownerStage);
                menuStage.setWidth(mw);
                menuStage.setHeight(mh);
                menuStage.setAlwaysOnTop(true);
                // 失焦自动隐藏
                menuStage.focusedProperty().addListener((_, _, newVal) -> {
                    if (!newVal) menuStage.hide();
                });

                VBox root = new VBox(2);
                root.setStyle("-fx-background-color: -color-bg-default;"
                        + " -fx-background-radius: 8; -fx-padding: 6;"
                        + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 10, 0, 0, 3);");
                Scene scene = new Scene(root);
                scene.setFill(null);
                menuStage.setScene(scene);
            }

            VBox root = (VBox) menuStage.getScene().getRoot();
            root.getChildren().setAll(
                    createItem("显示", () -> { showWindow(); menuStage.hide(); }),
                    createItem("退出", () -> { menuStage.hide(); Platform.runLater(Platform::exit); })
            );


            menuStage.setX(x);
            menuStage.setY(y);
            menuStage.show();
            menuStage.toFront();
        } catch (Exception e) {
            log.error("显示 JavaFX 菜单失败", e);
        }
    }

    private Label createItem(String text, Runnable action) {
        Label label = new Label(text);
        label.setPrefSize(150, 32);
        String normal = "-fx-padding: 4 12; -fx-font-size: 13;"
                + " -fx-background-radius: 6; -fx-text-fill: -color-fg-default;"
                + " -fx-background-color: transparent;";
        String hover = "-fx-padding: 4 12; -fx-font-size: 13;"
                + " -fx-background-radius: 6; -fx-text-fill: -color-fg-default;"
                + " -fx-background-color: -color-accent-subtle;";
        label.setStyle(normal);
        label.setOnMouseEntered(_ -> label.setStyle(hover));
        label.setOnMouseExited(_ -> label.setStyle(normal));
        label.setOnMouseClicked(_ -> action.run());
        return label;
    }

    // ---- 图标加载 ----

    private Image loadTrayImage() throws Exception {
        // 获取系统托盘期望的精确物理像素尺寸
        Dimension traySize = SystemTray.getSystemTray().getTrayIconSize();
        int tw = traySize.width;
        int th = traySize.height;
        if (tw < 1 || th < 1) { tw = 16; th = 16; }
        int size = Math.max(tw, th);

        // 优先从 SVG 渲染精确像素尺寸，避免 AWT 自动缩放
        try {
            WritableImage fxImg = new WritableImage(size, size);
            javafx.scene.Node iconNode = SvgManager.createIcon(PathConfig.ICON, size);
            // createIcon 在当前 FX 线程下 snapshot
            javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
            sp.setFill(Color.TRANSPARENT);
            iconNode.snapshot(sp, fxImg);

            BufferedImage bi = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
            PixelReader reader = fxImg.getPixelReader();
            int[] pixels = new int[tw * th];
            reader.getPixels(0, 0, tw, th, PixelFormat.getIntArgbInstance(), pixels, 0, tw);
            bi.setRGB(0, 0, tw, th, pixels, 0, tw);
            return Toolkit.getDefaultToolkit().createImage(bi.getSource());
        } catch (Exception e) {
            log.warn("SVG 渲染精确尺寸托盘图标失败", e);
        }

        // 降级：从外部 PNG 缩放至物理像素尺寸
        File iconFile = FilePathUtil.getExternalFile("icon", "/rmt.png");
        if (!iconFile.exists()) {
            File appDir = FilePathUtil.getAppRootDir().toFile();
            iconFile = new File(appDir, "rmt.png");
            if (!iconFile.exists()) {
                try (InputStream is = ResourceUtils.getResourceStream(PathConfig.ICON_PNG)) {
                    Files.copy(is, iconFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (iconFile.exists()) {
            BufferedImage src = javax.imageio.ImageIO.read(iconFile);
            BufferedImage scaled = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = scaled.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(src, 0, 0, tw, th, null);
            g2d.dispose();
            return Toolkit.getDefaultToolkit().createImage(scaled.getSource());
        }
        return null;
    }
}
