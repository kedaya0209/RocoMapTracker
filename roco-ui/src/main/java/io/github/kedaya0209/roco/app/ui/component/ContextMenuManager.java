package io.github.kedaya0209.roco.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.context.ResourcePointContext;
import io.github.kedaya0209.roco.app.map.model.ResourcePoint;
import io.github.kedaya0209.roco.app.ui.component.dialog.ConfirmDialog;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.StackPane;

/**
 * 右键菜单管理器 — 从 InteractiveCanvas 拆分，
 * 负责地图菜单和资源点菜单的创建与显示。
 */
@NotThreadSafe
public class ContextMenuManager {

    private final ContextMenu mapMenu = new ContextMenu();
    private final ContextMenu imageMenu = new ContextMenu();
    private final Node owner;
    private final Runnable onResetCamera;
    private final Runnable onMarkDirty;
    private double clickX, clickY;

    /**
     * @param owner         菜单依附的节点（InteractiveCanvas）
     * @param onResetCamera 重置视角回调
     * @param onMarkDirty   标记重绘回调
     */
    public ContextMenuManager(Node owner, Runnable onResetCamera, Runnable onMarkDirty) {
        this.owner = owner;
        this.onResetCamera = onResetCamera;
        this.onMarkDirty = onMarkDirty;
        initMapMenu();
    }

    /**
     * 记录右键按下时的坐标（供"在此处添加标记"使用）
     */
    public void setClickPoint(double x, double y) {
        this.clickX = x;
        this.clickY = y;
    }

    /**
     * 显示地图右键菜单
     */
    public void showMapMenu(double sx, double sy) {
        mapMenu.show(owner, sx, sy);
    }

    /**
     * 显示资源点右键菜单
     */
    public void showImageMenu(double sx, double sy, ResourcePoint p) {
        imageMenu.getItems().clear();

        MenuItem info = new MenuItem(p.getConfig().getMarkTypeName());
        info.setDisable(true);
        imageMenu.getItems().addAll(info, new SeparatorMenuItem());

        if (ResourcePointContext.getInstance().isCollect(p.getConfig().getMarkTypeName())) {
            MenuItem toggle = new MenuItem(p.isGrayed() ? "恢复标记" : "标记为已采集");
            Runnable markDirty = onMarkDirty;
            toggle.setOnAction(_ -> {
                p.setGrayed(!p.isGrayed());
                if (markDirty != null) markDirty.run();
            });
            imageMenu.getItems().add(toggle);
        }

        MenuItem del = new MenuItem("删除点位");
        del.setStyle("-fx-text-fill: #ff4444;");
        del.setOnAction(_ -> {
            StackPane rootStack = findRootStack();
            if (rootStack != null) {
                ConfirmDialog.showConfirmDialog(rootStack, "删除标记",
                        "确定要永久删除吗？",
                        "确认删除",
                        () -> ResourcePointContext.getInstance().deletePoint(p), null);
            }
        });
        imageMenu.getItems().add(del);

        imageMenu.show(owner, sx, sy);
    }

    /**
     * 隐藏所有菜单
     */
    public void hideAll() {
        if (mapMenu.isShowing()) mapMenu.hide();
        if (imageMenu.isShowing()) imageMenu.hide();
    }

    /**
     * 返回"在此处添加标记"的 X 坐标（逻辑坐标）
     */
    public double getClickX() {
        return clickX;
    }

    /**
     * 返回"在此处添加标记"的 Y 坐标（逻辑坐标）
     */
    public double getClickY() {
        return clickY;
    }

    // ================================================================
    // 内部方法
    // ================================================================

    private void initMapMenu() {
        MenuItem addPoint = new MenuItem("在此处添加标记");
        addPoint.setOnAction(_ -> {
            StackPane rootStack = findRootStack();
            if (rootStack != null) {
                AddPointDialog.open(rootStack, clickX, clickY);
            }
        });
        MenuItem resetCam = new MenuItem("重置视角");
        resetCam.setOnAction(_ -> onResetCamera.run());
        mapMenu.getItems().addAll(addPoint, new SeparatorMenuItem(), resetCam);
    }

    private StackPane findRootStack() {
        if (owner.getParent() != null && owner.getParent().getParent() instanceof StackPane sp) {
            return sp;
        }
        return null;
    }
}
