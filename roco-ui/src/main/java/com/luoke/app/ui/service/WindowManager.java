package com.luoke.app.ui.service;

import com.luoke.app.config.AppConfig;
import javafx.scene.Cursor;
import javafx.scene.layout.AnchorPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

/**
 * 物理边缘捕获器：通过透明矩形实现无边框缩放，解决光标不显示的问题
 */
public class WindowManager {

    private final int margin;
    private double xOffset = 0;
    private double yOffset = 0;
    private double maxWidth = Double.MAX_VALUE;
    private double maxHeight = Double.MAX_VALUE;

    public WindowManager(int margin) {
        this.margin = margin;
    }

    /**
     * 设置窗口最大尺寸（拖拽不可超过此值）
     */
    public void setMaxSize(double maxW, double maxH) {
        this.maxWidth = maxW;
        this.maxHeight = maxH;
    }

    public void install(Stage stage, AnchorPane resizeLayer) {
        // 创建 8 个方向的物理感应区
        Rectangle n = createEdge(Cursor.N_RESIZE);
        Rectangle s = createEdge(Cursor.S_RESIZE);
        Rectangle e = createEdge(Cursor.E_RESIZE);
        Rectangle w = createEdge(Cursor.W_RESIZE);
        Rectangle ne = createEdge(Cursor.NE_RESIZE);
        Rectangle nw = createEdge(Cursor.NW_RESIZE);
        Rectangle se = createEdge(Cursor.SE_RESIZE);
        Rectangle sw = createEdge(Cursor.SW_RESIZE);

        // 绑定位置与尺寸
        n.widthProperty().bind(resizeLayer.widthProperty().subtract(margin * 2));
        n.setHeight(margin);
        n.setTranslateX(margin);

        s.widthProperty().bind(resizeLayer.widthProperty().subtract(margin * 2));
        s.setHeight(margin);
        s.setTranslateX(margin);
        s.layoutYProperty().bind(resizeLayer.heightProperty().subtract(margin));

        w.heightProperty().bind(resizeLayer.heightProperty().subtract(margin * 2));
        w.setWidth(margin);
        w.setTranslateY(margin);

        e.heightProperty().bind(resizeLayer.heightProperty().subtract(margin * 2));
        e.setWidth(margin);
        e.setTranslateY(margin);
        e.layoutXProperty().bind(resizeLayer.widthProperty().subtract(margin));

        nw.setWidth(margin);
        nw.setHeight(margin);
        ne.setWidth(margin);
        ne.setHeight(margin);
        ne.layoutXProperty().bind(resizeLayer.widthProperty().subtract(margin));
        sw.setWidth(margin);
        sw.setHeight(margin);
        sw.layoutYProperty().bind(resizeLayer.heightProperty().subtract(margin));
        se.setWidth(margin);
        se.setHeight(margin);
        se.layoutXProperty().bind(resizeLayer.widthProperty().subtract(margin));
        se.layoutYProperty().bind(resizeLayer.heightProperty().subtract(margin));

        // 统一安装事件逻辑
        setupDrag(stage, n, false, true, false, -1);
        setupDrag(stage, s, false, false, false, 1);
        setupDrag(stage, e, false, false, 1, false);
        setupDrag(stage, w, true, false, -1, false);
        setupDrag(stage, ne, false, true, 1, -1);
        setupDrag(stage, nw, true, true, -1, -1);
        setupDrag(stage, se, false, false, 1, 1);
        setupDrag(stage, sw, true, false, -1, 1);

        resizeLayer.getChildren().addAll(n, s, e, w, ne, nw, se, sw);
    }

    private Rectangle createEdge(Cursor cursor) {
        Rectangle r = new Rectangle();
        r.setFill(Color.TRANSPARENT); // 保持透明，解决“丑边框”
        r.setCursor(cursor);
        r.setManaged(false); // 不参与布局计算，防止挤压 Canvas
        return r;
    }

    private void setupDrag(Stage stage, Rectangle r, boolean moveX, boolean moveY, Object resizeX, Object resizeY) {
        r.setOnMousePressed(e -> {
            xOffset = e.getScreenX();
            yOffset = e.getScreenY();
        });

        r.setOnMouseDragged(e -> {
            double deltaX = e.getScreenX() - xOffset;
            double deltaY = e.getScreenY() - yOffset;

            if (resizeX instanceof Integer dirX) {
                double newW = stage.getWidth() + (deltaX * dirX);
                if (newW > AppConfig.MIN_WINDOW_WIDTH && newW <= maxWidth) {
                    if (moveX) stage.setX(stage.getX() + deltaX);
                    stage.setWidth(newW);
                }
            }

            if (resizeY instanceof Integer dirY) {
                double newH = stage.getHeight() + (deltaY * dirY);
                if (newH > AppConfig.MIN_WINDOW_HEIGHT && newH <= maxHeight) {
                    if (moveY) stage.setY(stage.getY() + deltaY);
                    stage.setHeight(newH);
                }
            }

            xOffset = e.getScreenX();
            yOffset = e.getScreenY();
        });
    }
}