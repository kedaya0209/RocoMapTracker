package io.github.kedaya0209.roco.app.ui.component.widget;

import io.github.kedaya0209.roco.app.context.MapContext;
import io.github.kedaya0209.roco.app.context.ResourcePointContext;
import io.github.kedaya0209.roco.app.map.loader.ImageLoader;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.SetFollowModeCommand;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.SetLayerCommand;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.ToggleMaterialCollectionCommand;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.ToggleResourceTypeCommand;
import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.ui.command.CommandBus;
import io.github.kedaya0209.roco.app.ui.component.overlay.ResourceCounterPanel;
import io.github.kedaya0209.roco.app.ui.service.VersionMode;
import io.github.kedaya0209.roco.app.ui.service.resource.SvgManager;
import io.github.kedaya0209.roco.app.ui.service.ui.VersionManager;
import io.github.kedaya0209.roco.app.ui.state.AppState;
import io.github.kedaya0209.roco.app.ui.state.ViewportState;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import javafx.util.Duration;
import lombok.Getter;
import net.jcip.annotations.NotThreadSafe;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@NotThreadSafe
public class FloatToolbox extends VBox {
    @Getter
    private static volatile FloatToolbox instance;
    @Getter
    private final ResourceCounterPanel resourcePanel;
    private final StackPane collectBtn;
    private final VBox leftCol;

    private record LayerGroup(int layer, StackPane btn) {}

    // 大陆/层切换按钮
    private final StackPane mainlandBtn;
    private final List<LayerGroup> layerGroups = new ArrayList<>();
    @Getter
    private boolean resourcePanelVisible = false;
    private final Map<Integer, VBox> caveColumnsByLayer = new HashMap<>();
    private final Pane cavePane;
    // UI 选中层（仅展开洞穴按钮列表，不加载瓦片）
    private int selectedLayer = -1;
    private final Image mainlandImg;
    private final Image mainlandActiveImg;
    private final Image coverImg;
    private final Image coverActiveImg;

    // 资源筛选
    private final StackPane filterBtn;
    private final VBox filterPanel;
    private boolean filterExpanded = false;
    private boolean filterPanelBuilt = false;
    private static final double FILTER_PANEL_WIDTH = 220;
    private static final double FILTER_PANEL_MAX_HEIGHT = 400;
    private static final int FILTER_ICON_SIZE = 20;

    public FloatToolbox(ResourceCounterPanel resourcePanel, String unifiedBlueColor) {
        super(12);
        instance = this;
        this.resourcePanel = resourcePanel;
        setPadding(new Insets(10));
        setAlignment(Pos.TOP_CENTER);
        setPickOnBounds(false);
        setStyle("-fx-background-color: transparent;");

        // 加载 PNG 图标
        try {
            this.mainlandImg = new Image(ResourceUtils.getResourceStream("/icon/mainland.png"));
            this.mainlandActiveImg = new Image(ResourceUtils.getResourceStream("/icon/mainland_active.png"));
            this.coverImg = new Image(ResourceUtils.getResourceStream("/icon/cover.png"));
            this.coverActiveImg = new Image(ResourceUtils.getResourceStream("/icon/cover_active.png"));

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // 左侧列：全部按钮（筛选、跟随、大陆、层按钮）
        leftCol = new VBox(12);
        leftCol.setAlignment(Pos.TOP_CENTER);

        // 资源筛选按钮放最上面
        filterBtn = createFilterButton(unifiedBlueColor);
        leftCol.getChildren().add(filterBtn);

        StackPane followBtn = createFollowButton(unifiedBlueColor);
        mainlandBtn = createMainlandButton();
        leftCol.getChildren().addAll(followBtn, mainlandBtn);

        // 构建层按钮 + 右侧洞穴按钮列
        cavePane = new Pane();
        cavePane.setPickOnBounds(false);

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

                List<Integer> caveIndices = MapContext.getInstance().getCaveIndicesForLayer(layer);
                VBox caveCol = new VBox(4);
                caveCol.setAlignment(Pos.TOP_CENTER);
                caveCol.setManaged(false); // Pane 不干涉子节点布局
                caveCol.setVisible(false);
                if (caveIndices.size() > 1) {
                    for (int idx : caveIndices) {
                        StackPane caveBtn = createCaveButton(idx);
                        caveCol.getChildren().add(caveBtn);
                    }
                }
                caveColumnsByLayer.put(layer, caveCol);
                cavePane.getChildren().add(caveCol);
            }
        }

        // 资源筛选展开面板（右侧覆盖层，首次点击时才填充内容）
        filterPanel = new VBox(6);
        filterPanel.setStyle("-fx-background-color: -color-bg-default; -fx-background-radius: 6; " +
                "-fx-border-color: -color-border-muted; -fx-border-radius: 6;");
        filterPanel.setManaged(false);
        filterPanel.setVisible(false);
        filterPanel.setMinWidth(FILTER_PANEL_WIDTH);
        filterPanel.setMaxWidth(FILTER_PANEL_WIDTH);
        Pane filterPaneOverlay = new Pane();
        filterPaneOverlay.setPickOnBounds(false);
        filterPaneOverlay.translateXProperty().bind(leftCol.widthProperty().add(16));
        filterPaneOverlay.setMaxSize(0, 0);
        filterPaneOverlay.getChildren().add(filterPanel);

        // 主布局：左侧按钮列 + 右侧覆盖层
        StackPane layoutStack = new StackPane();
        layoutStack.setAlignment(Pos.TOP_LEFT);
        HBox leftWrapper = new HBox(leftCol);
        layoutStack.getChildren().add(leftWrapper);
        cavePane.translateXProperty().bind(leftCol.widthProperty().add(16));
        // 防止 StackPane 拉伸 cavePane
        cavePane.setMaxSize(0, 0);
        layoutStack.getChildren().add(cavePane);
        layoutStack.getChildren().add(filterPaneOverlay);
        getChildren().add(layoutStack);

        // 资源计数切换（仅在高级版显示）
        collectBtn = createVectorIconButton(
                "资源采集计数",
                "M9 7V5h6v2h2V5a2 2 0 0 0-2-2H9a2 2 0 0 0-2 2v2h2zm11 8V9a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2zm-11-4h4v2h-4v-2z",
                resourcePanel, unifiedBlueColor
        );

        if (VersionManager.getInstance().getCurrentMode() == VersionMode.ADVANCED) {
            insertCollectButton();
        }
        updateCaveButtonStates();
    }

    private void insertCollectButton() {
        ObservableList<Node> children = leftCol.getChildren();
        if (children.contains(collectBtn)) {
            return;
        }
        children.add(1, collectBtn);
    }

    public void setCollectButtonVisible(boolean visible) {
        if (visible) {
            insertCollectButton();
            return;
        }
        leftCol.getChildren().remove(collectBtn);
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

        // 右侧洞穴列 — 仅显示选中层，中心与对应层按钮对齐
        for (Map.Entry<Integer, VBox> entry : caveColumnsByLayer.entrySet()) {
            boolean active = entry.getKey() == selectedLayer;
            VBox caveCol = entry.getValue();
            caveCol.setVisible(active);
            if (active) {
                // 计算层按钮的中心 Y（场景坐标），将洞穴列中心与之对齐
                for (LayerGroup lg : layerGroups) {
                    if (lg.layer() == selectedLayer) {
                        double btnCenterSceneY = lg.btn().localToScene(
                                lg.btn().getBoundsInLocal().getCenterX(),
                                lg.btn().getBoundsInLocal().getCenterY()).getY();
                        int n = caveCol.getChildren().size();
                        double colHeight = n * 46.0 + (n - 1) * 4.0;
                        double localY = cavePane.sceneToLocal(0, btnCenterSceneY).getY();
                        caveCol.setLayoutY(localY - colHeight / 2);
                        break;
                    }
                }
                // 更新洞穴按钮高亮
                for (Node child : caveCol.getChildren()) {
                    if (child instanceof StackPane caveBtn) {
                        int caveIdx = (int) caveBtn.getUserData();
                        ((ImageView) caveBtn.getChildren().get(0)).setImage(
                                overrideIdx == caveIdx ? coverActiveImg : coverImg);
                    }
                }
            }
        }
    }

    // ==================== 资源筛选 ====================

    /**
     * 筛选图标按钮：点击展开/收起筛选面板，首次展开时从 ResourcePointContext 动态构建面板内容。
     */
    private StackPane createFilterButton(String unifiedBlueColor) {
        StackPane btn = new StackPane();
        btn.getStyleClass().add("float-toolbox-btn");

        SVGPath icon = new SVGPath();
        icon.setContent("M10 18h4v-2h-4v2zM3 6v2h18V6H3zm3 7h12v-2H6v2z");
        icon.setFill(Color.WHITE);
        icon.setStroke(Color.BLACK);
        icon.setStrokeWidth(0.2);
        icon.setScaleX(1.3);
        icon.setScaleY(1.3);

        Tooltip tooltip = new Tooltip("资源筛选");
        tooltip.setShowDelay(Duration.millis(150));
        Tooltip.install(btn, tooltip);
        btn.getChildren().add(icon);

        btn.setOnMouseClicked(_ -> {
            filterExpanded = !filterExpanded;
            if (filterExpanded) {
                if (!filterPanelBuilt) {
                    buildFilterPanel();
                    filterPanelBuilt = true;
                }
                // 展开时显式 resize + layout（managed=false 不会自动布局）
                filterPanel.applyCss();
                double h = Math.min(filterPanel.prefHeight(FILTER_PANEL_WIDTH), FILTER_PANEL_MAX_HEIGHT);
                filterPanel.resize(FILTER_PANEL_WIDTH, h);
                filterPanel.layout();
                // 面板定位到与筛选按钮中心对齐（不超出标题栏下方）
                double btnCenterSceneY = btn.localToScene(
                        btn.getBoundsInLocal().getCenterX(),
                        btn.getBoundsInLocal().getCenterY()).getY();
                double localY = filterPanel.getParent().sceneToLocal(0, btnCenterSceneY).getY();
                double layoutY = localY - h / 2;
                double minLayoutY = filterPanel.getParent().sceneToLocal(0, 48).getY();
                if (layoutY < minLayoutY) {
                    layoutY = minLayoutY;
                }
                filterPanel.setLayoutY(layoutY);
            }
            filterPanel.setVisible(filterExpanded);
            icon.setFill(filterExpanded ? Color.web(unifiedBlueColor) : Color.WHITE);
        });

        return btn;
    }

    /**
     * 从 ResourcePointContext 动态构建筛选面板：按 type 分组，每组下列出图标+名称条目。
     */
    private void buildFilterPanel() {
        filterPanel.getChildren().clear();

        VBox content = new VBox(6);
        content.setPadding(new Insets(8));

        ResourcePointContext rpc = ResourcePointContext.getInstance();
        List<String> types = rpc.getResourceTypes();
        for (String type : types) {
            List<String> names = rpc.getResourceNamesByType(type);
            if (names.isEmpty()) continue;

            VBox section = new VBox(3);

            // 可点击的类型标题：点击切换该分类下所有名称
            HBox headerBox = new HBox(4);
            headerBox.setAlignment(Pos.CENTER_LEFT);
            headerBox.setStyle("-fx-cursor: hand;");
            Label header = new Label(type + " (" + names.size() + ")");
            header.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px; -fx-font-weight: bold;");
            headerBox.getChildren().add(header);

            FlowPane items = new FlowPane(4, 4);
            for (String name : names) {
                items.getChildren().add(createFilterItem(name));
            }

            // 点击标题切换该分类全部显示/隐藏
            headerBox.setOnMouseClicked(_ -> {
                AppState app = AppState.getInstance();
                boolean allOn = names.stream().allMatch(n -> app.getResourceFilter(n).get());
                for (String name : names) {
                    boolean current = app.getResourceFilter(name).get();
                    if (current == allOn) {
                        CommandBus.dispatch(new ToggleResourceTypeCommand(name));
                    }
                }
            });

            section.getChildren().addAll(headerBox, items);
            content.getChildren().add(section);
        }

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-padding: 0;");
        scrollPane.setMaxHeight(FILTER_PANEL_MAX_HEIGHT);
        filterPanel.getChildren().add(scrollPane);
    }

    /**
     * 创建单个资源名称筛选条目：[图标 20x20] [名称文字]，点击切换显隐。
     */
    private HBox createFilterItem(String resourceName) {
        HBox item = new HBox(4);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(2));
        item.setStyle("-fx-cursor: hand;");

        // 资源图标
        ImageView iconView = new ImageView();
        iconView.setFitWidth(FILTER_ICON_SIZE);
        iconView.setFitHeight(FILTER_ICON_SIZE);
        iconView.setPreserveRatio(true);
        String iconFile = ResourcePointContext.getInstance().getIconForName(resourceName);
        if (iconFile != null) {
            try {
                String path = PathConfig.ICON_DIR + iconFile;
                byte[] bytes = ImageLoader.getInstance().loadIconBytes(path);
                if (bytes != null) {
                    iconView.setImage(new Image(new ByteArrayInputStream(bytes)));
                }
            } catch (Exception ignored) {
            }
        }

        // 名称
        Label nameLabel = new Label(resourceName);
        nameLabel.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 11px;");
        nameLabel.setMaxWidth(140);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        item.getChildren().addAll(iconView, nameLabel);

        // 绑定筛选状态
        item.opacityProperty().bind(Bindings
                .when(AppState.getInstance().getResourceFilter(resourceName))
                .then(1.0).otherwise(0.35));

        item.setOnMouseClicked(_ -> CommandBus.dispatch(new ToggleResourceTypeCommand(resourceName)));

        return item;
    }
}
