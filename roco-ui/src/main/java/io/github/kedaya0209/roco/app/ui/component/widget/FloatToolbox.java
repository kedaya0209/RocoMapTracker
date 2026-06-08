package io.github.kedaya0209.roco.app.ui.component.widget;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.ui.component.overlay.ResourceCounterPanel;
import io.github.kedaya0209.roco.app.context.MapContext;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.SetLayerCommand;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.SetFollowModeCommand;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.ToggleMaterialCollectionCommand;
import io.github.kedaya0209.roco.app.ui.command.CommandBus;
import io.github.kedaya0209.roco.app.ui.state.AppState;
import javafx.beans.binding.Bindings;
import io.github.kedaya0209.roco.app.ui.service.VersionMode;
import io.github.kedaya0209.roco.app.ui.service.resource.SvgManager;
import io.github.kedaya0209.roco.app.ui.service.ui.VersionManager;
import io.github.kedaya0209.roco.app.ui.state.ViewportState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@NotThreadSafe
public class FloatToolbox extends VBox {
    private static volatile FloatToolbox instance;

    private boolean resourcePanelVisible = false;
    private final StackPane collectBtn;
    private final ResourceCounterPanel resourcePanel;

    private record LayerGroup(int layer, StackPane btn) {}

    // 大陆/层切换按钮
    private final StackPane mainlandBtn;
    private final List<LayerGroup> layerGroups = new ArrayList<>();
    // 右侧列 — 当前选中层的洞穴选择按钮
    private final VBox rightCol;
    private final Map<Integer, List<StackPane>> caveButtonsByLayer = new HashMap<>();
    // UI 选中层（仅展开洞穴按钮列表，不加载瓦片）
    private int selectedLayer = -1;
    private final Image mainlandImg;
    private final Image mainlandActiveImg;
    private final Image coverImg;
    private final Image coverActiveImg;

    public static FloatToolbox getInstance() {
        return instance;
    }

    public FloatToolbox(ResourceCounterPanel resourcePanel, String unifiedBlueColor) {
        super(12);
        instance = this;
        this.resourcePanel = resourcePanel;
        setPadding(new Insets(10));
        setAlignment(Pos.TOP_CENTER);
        setPickOnBounds(false);
        setStyle("-fx-background-color: transparent;");

        // 加载 PNG 图标
        this.mainlandImg = new Image(getClass().getResourceAsStream("/icon/mainland.png"));
        this.mainlandActiveImg = new Image(getClass().getResourceAsStream("/icon/mainland_active.png"));
        this.coverImg = new Image(getClass().getResourceAsStream("/icon/cover.png"));
        this.coverActiveImg = new Image(getClass().getResourceAsStream("/icon/cover_active.png"));

        // 左侧列：主图标
        VBox leftCol = new VBox(12);
        leftCol.setAlignment(Pos.TOP_CENTER);

        StackPane followBtn = createFollowButton(unifiedBlueColor);
        mainlandBtn = createMainlandButton();
        leftCol.getChildren().addAll(followBtn, mainlandBtn);

        // 构建层按钮（左列）+ 洞穴按钮（右列）
        CompositeMapMetadata meta = MapContext.getInstance().getMultiMapMetadata();
        if (meta != null) {
            List<Integer> caveLayers = meta.subImages().stream()
                    .filter(CompositeMapMetadata.SubImageInfo::isCave)
                    .map(CompositeMapMetadata.SubImageInfo::layer)
                    .distinct()
                    .sorted()
                    .toList();
            for (int layer : caveLayers) {
                String label = layer == 0 ? "洞穴" : "第" + layer + "层";
                StackPane layerBtn = createLayerButton(label, layer);
                layerGroups.add(new LayerGroup(layer, layerBtn));
                leftCol.getChildren().add(layerBtn);

                // 仅子图 >1 的层才需要子图标，预创建洞穴按钮
                List<Integer> caveIndices = MapContext.getInstance().getCaveIndicesForLayer(layer);
                if (caveIndices.size() > 1) {
                    List<StackPane> caveBtns = new ArrayList<>();
                    for (int idx : caveIndices) {
                        StackPane caveBtn = createCaveButton(idx);
                        caveBtn.setVisible(false);
                        caveBtn.setManaged(false);
                        caveBtns.add(caveBtn);
                    }
                    caveButtonsByLayer.put(layer, caveBtns);
                }
            }
        }

        // 右侧列：子图 >1 的层对应的洞穴按钮列（默认隐藏）
        rightCol = new VBox(4);
        rightCol.setVisible(false);
        rightCol.setAlignment(Pos.TOP_CENTER);
        for (List<StackPane> btns : caveButtonsByLayer.values()) {
            rightCol.getChildren().addAll(btns);
        }

        // 主行：左列 + 右列
        HBox mainRow = new HBox(8);
        mainRow.setAlignment(Pos.TOP_CENTER);
        mainRow.getChildren().addAll(leftCol, rightCol);
        getChildren().add(mainRow);

        // 资源计数切换（仅在高级版显示）
        collectBtn = createVectorIconButton(
                "资源采集计数",
                "M9 7V5h6v2h2V5a2 2 0 0 0-2-2H9a2 2 0 0 0-2 2v2h2zm11 8V9a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2zm-11-4h4v2h-4v-2z",
                resourcePanel, unifiedBlueColor
        );

        if (VersionManager.getInstance().getCurrentMode() == VersionMode.ADVANCED) {
            getChildren().add(collectBtn);
        }
        updateCaveButtonStates();
    }

    public void setCollectButtonVisible(boolean visible) {
        if (visible && !getChildren().contains(collectBtn)) {
            getChildren().add(collectBtn);
        } else if (!visible) {
            getChildren().remove(collectBtn);
        }
    }

    /**
     * 创建跟随模式按钮（从 follow.svg 加载，自动 Group 包裹处理 1024 规格）
     */
    private StackPane createFollowButton(String unifiedBlueColor) {
        StackPane btn = new StackPane();
        btn.getStyleClass().add("float-toolbox-btn");

        SVGPath icon = new SVGPath();
        icon.setContent(SvgManager.getPath("/icon/follow.svg"));
        icon.setFill(Color.WHITE);
        icon.setStroke(Color.BLACK);
        icon.setStrokeWidth(0.2);
        // follow.svg viewBox=1024，缩放到与原图钉图标相同视觉尺寸（~31px）
        double scale = 31.0 / 1024.0;
        icon.getTransforms().add(new Scale(scale, scale));

        // Group 包裹使 layoutBounds = 变换后尺寸，StackPane 按钮不会撑大
        Group wrapper = new Group(icon);

        Tooltip tooltip = new Tooltip("自动跟随模式 (Space)");
        tooltip.setShowDelay(Duration.millis(150));
        Tooltip.install(btn, tooltip);
        btn.getChildren().add(wrapper);

        btn.setOnMouseClicked(_ ->
                CommandBus.dispatch(new SetFollowModeCommand(!ViewportState.getInstance().isFollowMode())));
        icon.fillProperty().bind(Bindings
                .when(ViewportState.getInstance().followModeProperty())
                .then(Color.web(unifiedBlueColor))
                .otherwise(Color.WHITE));

        return btn;
    }

    private StackPane createVectorIconButton(String hint, String svgPath, ResourceCounterPanel panel, String unifiedBlueColor) {
        StackPane btn = new StackPane();
        btn.getStyleClass().add("float-toolbox-btn");

        SVGPath icon = new SVGPath();
        icon.setContent(svgPath);
        icon.setFill(Color.WHITE);
        icon.setStroke(Color.BLACK);
        icon.setStrokeWidth(0.2);
        icon.setScaleX(1.3);
        icon.setScaleY(1.3);

        Tooltip tooltip = new Tooltip(hint);
        tooltip.setShowDelay(Duration.millis(150));
        Tooltip.install(btn, tooltip);
        btn.getChildren().add(icon);

        if (panel != null) {
            // Property listener 驱动面板和图标（handler 只 dispatch command）
            AppState.getInstance().materialCollectionProperty().addListener((_, _, now) -> {
                resourcePanelVisible = now;
                panel.toggle(now);
                icon.setFill(now ? Color.web(unifiedBlueColor) : Color.WHITE);
            });
            btn.setOnMouseClicked(_ ->
                    CommandBus.dispatch(new ToggleMaterialCollectionCommand()));
        }
        return btn;
    }

    // ==================== 层切换按钮 ====================

    /**
     * 大陆按钮：点击返回自动模式（跟随匹配结果）。
     */
    private StackPane createMainlandButton() {
        StackPane btn = new StackPane();
        btn.getStyleClass().add("float-toolbox-btn");

        ImageView iconView = new ImageView(mainlandImg);
        iconView.setFitWidth(26);
        iconView.setFitHeight(26);
        iconView.setPreserveRatio(true);

        Tooltip tooltip = new Tooltip("大陆");
        tooltip.setShowDelay(Duration.millis(150));
        Tooltip.install(btn, tooltip);
        btn.getChildren().add(iconView);

        btn.setOnMouseClicked(_ -> {
            CommandBus.dispatch(new SetLayerCommand(-1));
            MapContext.getInstance().resetOverrideCaveIndex();
            selectedLayer = -1;
            updateCaveButtonStates();
        });

        return btn;
    }

    /**
     * 创建层切换按钮：点击在该层与自动模式之间切换。
     * 激活时右侧列显示该层所有洞穴的选择按钮。
     * 按钮右下角显示该层包含的子图数量徽标。
     */
    private StackPane createLayerButton(String hint, int layer) {
        StackPane btn = new StackPane();
        btn.getStyleClass().add("float-toolbox-btn");

        ImageView iconView = new ImageView(coverImg);
        iconView.setFitWidth(26);
        iconView.setFitHeight(26);
        iconView.setPreserveRatio(true);
        btn.getChildren().add(iconView);

        // 右下角显示该层子图数量
        int caveCount = countCavesInLayer(layer);
        if (caveCount > 1) {
            Label badge = new Label(String.valueOf(caveCount));
            badge.setStyle("-fx-font-size: 8; -fx-text-fill: white; -fx-font-weight: bold; " +
                    "-fx-background-color: rgba(0,0,0,0.6); -fx-background-radius: 6; " +
                    "-fx-padding: 1 3;");
            StackPane.setAlignment(badge, Pos.BOTTOM_RIGHT);
            btn.getChildren().add(badge);
        }

        Tooltip tooltip = new Tooltip(hint + " (" + caveCount + "个洞穴)");
        tooltip.setShowDelay(Duration.millis(150));
        Tooltip.install(btn, tooltip);

        btn.setOnMouseClicked(_ -> {
            MapContext mc = MapContext.getInstance();
            if (selectedLayer == layer) {
                // 取消选中：隐藏右侧列，重置瓦片
                selectedLayer = -1;
                CommandBus.dispatch(new SetLayerCommand(-1));
                mc.resetOverrideCaveIndex();
            } else if (countCavesInLayer(layer) <= 1) {
                // 单洞穴层：直接加载该洞穴瓦片
                selectedLayer = layer;
                List<Integer> indices = mc.getCaveIndicesForLayer(layer);
                if (!indices.isEmpty()) {
                    mc.setActiveLayer(layer);
                    mc.setOverrideCaveIndex(indices.get(0));
                }
            } else {
                // 多洞穴层：仅展开洞穴按钮列表，不加载瓦片
                selectedLayer = layer;
                // 如果之前有单个洞穴瓦片正在显示，需卸载
                if (mc.getActiveLayer() >= 0) {
                    CommandBus.dispatch(new SetLayerCommand(-1));
                    mc.resetOverrideCaveIndex();
                }
            }
            updateCaveButtonStates();
        });

        return btn;
    }

    /**
     * 创建单独洞穴按钮：点击后仅显示该洞穴瓦片（不显示同层其它洞穴）。
     */
    private StackPane createCaveButton(int subImageIndex) {
        StackPane btn = new StackPane();
        btn.getStyleClass().add("float-toolbox-btn");
        btn.setUserData(subImageIndex); // 用于 updateCaveButtonStates 识别洞穴索引

        ImageView iconView = new ImageView(coverImg);
        iconView.setFitWidth(26);
        iconView.setFitHeight(26);
        iconView.setPreserveRatio(true);
        btn.getChildren().add(iconView);

        // 取洞穴名称作为 tooltip
        CompositeMapMetadata meta = MapContext.getInstance().getMultiMapMetadata();
        String caveName = (meta != null && subImageIndex < meta.subImages().size())
                ? meta.subImages().get(subImageIndex).name() : "洞穴";
        Tooltip tooltip = new Tooltip(caveName);
        tooltip.setShowDelay(Duration.millis(150));
        Tooltip.install(btn, tooltip);

        btn.setOnMouseClicked(_ -> {
            MapContext mc = MapContext.getInstance();
            int caveLayer = getLayerForCave(subImageIndex);
            selectedLayer = caveLayer; // 同步选中层，确保右侧列显示正确
            mc.setActiveLayer(caveLayer);
            mc.setOverrideCaveIndex(subImageIndex);
            updateCaveButtonStates();
        });

        return btn;
    }

    private static int getLayerForCave(int subImageIndex) {
        CompositeMapMetadata meta = MapContext.getInstance().getMultiMapMetadata();
        if (meta == null || subImageIndex >= meta.subImages().size()) return -1;
        return meta.subImages().get(subImageIndex).layer();
    }

    /**
     * 统计指定 layer 的洞穴子图数量。
     */
    private static int countCavesInLayer(int layer) {
        CompositeMapMetadata meta = MapContext.getInstance().getMultiMapMetadata();
        if (meta == null) return 0;
        return (int) meta.subImages().stream()
                .filter(s -> s.isCave() && s.layer() == layer)
                .count();
    }

    /**
     * 根据当前 selectedLayer + overrideCaveIndex 更新所有按钮的图标状态。
     * <ul>
     *   <li>大陆按钮：全空态（未选中层、无洞穴加载）时高亮</li>
     *   <li>层按钮：该层被选中（selectedLayer == layer）时高亮</li>
     *   <li>右侧列：仅在选中某层时可见，显示该层各洞穴按钮</li>
     *   <li>洞穴按钮：被 overrideCaveIndex 选中时高亮</li>
     * </ul>
     */
    private void updateCaveButtonStates() {
        MapContext mc = MapContext.getInstance();
        int overrideIdx = mc.getOverrideCaveIndex();

        // 大陆按钮：selectedLayer == -1 且无洞穴加载时高亮
        boolean mainlandActive = selectedLayer < 0 && mc.getActiveLayer() < 0;
        ((ImageView) mainlandBtn.getChildren().get(0)).setImage(
                mainlandActive ? mainlandActiveImg : mainlandImg);

        // 层按钮：selectedLayer 命中时高亮
        for (LayerGroup lg : layerGroups) {
            boolean layerSelected = selectedLayer == lg.layer();
            ((ImageView) lg.btn().getChildren().get(0)).setImage(
                    layerSelected ? coverActiveImg : coverImg);
        }

        // 右侧列：仅在有选中层且该层有子图标时可见
        boolean hasSelectedLayer = selectedLayer >= 0 && caveButtonsByLayer.containsKey(selectedLayer);
        rightCol.setVisible(hasSelectedLayer);
        rightCol.setManaged(hasSelectedLayer);
        if (hasSelectedLayer) {
            for (Map.Entry<Integer, List<StackPane>> entry : caveButtonsByLayer.entrySet()) {
                boolean isActiveLayer = entry.getKey() == selectedLayer;
                for (StackPane caveBtn : entry.getValue()) {
                    caveBtn.setVisible(isActiveLayer);
                    caveBtn.setManaged(isActiveLayer);
                    int caveIdx = (int) caveBtn.getUserData();
                    ((ImageView) caveBtn.getChildren().get(0)).setImage(
                            overrideIdx == caveIdx ? coverActiveImg : coverImg);
                }
            }
        }
    }
}
