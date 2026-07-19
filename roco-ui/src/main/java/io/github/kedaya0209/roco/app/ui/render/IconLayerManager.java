package io.github.kedaya0209.roco.app.ui.render;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.config.RenderConfig;
import io.github.kedaya0209.roco.app.config.ViewConfig;
import io.github.kedaya0209.roco.app.context.ResourcePointContext;
import io.github.kedaya0209.roco.app.map.model.Point;
import io.github.kedaya0209.roco.app.map.model.ResourcePoint;
import io.github.kedaya0209.roco.app.ui.service.resource.IconCache;
import io.github.kedaya0209.roco.app.ui.state.AppState;
import io.github.kedaya0209.roco.app.ui.state.ViewportState;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图标管理层 — 资源点 ImageView 构建 + 变灰切换。
 * <p>
 * 所有 ImageView 放入 iconGroup（worldGroup 子级），与瓦片共用 GPU 变换。
 * 变灰直接切换 ImageView 的 image 引用（colorAtlas ↔ grayAtlas），零 CPU 重绘。
 */
@NotThreadSafe
@Slf4j
public class IconLayerManager implements RenderLayer {

    private final Group iconGroup;
    private final Map<ResourcePoint, ImageView> iconViews = new HashMap<>();
    /** 按 markTypeName 分组的子 Group，用于筛选显隐 */
    private final Map<String, Group> nameGroups = new HashMap<>();
    private double lastGrayCheckX = Double.NaN;
    private double lastGrayCheckY = Double.NaN;
    /** 上次 counter-rotate 角度，用于脏检测 */
    private double lastCounterRotate = 0;

    public IconLayerManager(Group worldGroup) {
        iconGroup = new Group();
        iconGroup.setPickOnBounds(false);
        iconGroup.setMouseTransparent(true);
        worldGroup.getChildren().add(iconGroup);
    }

    @Override
    public Node getNode() {
        return iconGroup;
    }

    @Override
    public void onFrame() {
        // 导航模式 counter-rotate：抵消 worldGroup 的旋转，保持图标朝上
        ViewportState vp = ViewportState.getInstance();
        double counterAngle = vp.isNavMode() ? vp.getNavAngle() : 0;
        if (Math.abs(counterAngle - lastCounterRotate) > 0.01) {
            for (ImageView iv : iconViews.values()) {
                iv.setRotate(counterAngle);
            }
            lastCounterRotate = counterAngle;
        }

        ViewportState vps = ViewportState.getInstance();
        if (vps.isPlayerInitialized()) {
            updateGrayStates(vps.getPlayerX(), vps.getPlayerY());
        }
    }

    /**
     * 为每个资源点创建 ImageView，按 markTypeName 分组放入 iconGroup。
     * 筛选时按名称粒度显隐对应的 Group。
     */
    public void buildIconLayer() {
        IconCache cache = IconCache.getInstance();
        if (!cache.isAtlasReady()) {
            log.warn("图集未就绪，跳过图标层构建");
            return;
        }

        nameGroups.clear();
        iconGroup.getChildren().clear();

        Image colorAtlas = cache.getColorAtlas();
        Image grayAtlas = cache.getGrayAtlas();
        AppState app = AppState.getInstance();
        int built = 0;

        for (ResourcePoint rp : ResourcePointContext.getInstance().getAllPoints()) {
            String iconFile = rp.getConfig().getIcon();
            if (iconFile == null || iconFile.isEmpty()) continue;

            String iconPath = PathConfig.ICON_DIR + iconFile;
            IconCache.AtlasSlot slot = cache.getSlot(iconPath);
            if (slot == null) continue;

            ImageView iv = new ImageView();
            iv.setImage(rp.isGrayed() ? grayAtlas : colorAtlas);
            iv.setViewport(new Rectangle2D(slot.sx(), slot.sy(), RenderConfig.ICON_SIZE, RenderConfig.ICON_SIZE));
            iv.setPreserveRatio(false);
            iv.setSmooth(false);
            iv.setMouseTransparent(true);
            iv.setPickOnBounds(false);

            Point pos = rp.getScreenPosition();
            iv.setLayoutX(pos.getX() - RenderConfig.ICON_SIZE / 2.0);
            iv.setLayoutY(pos.getY() - RenderConfig.ICON_SIZE / 2.0);
            iv.setFitWidth(RenderConfig.ICON_SIZE);
            iv.setFitHeight(RenderConfig.ICON_SIZE);

            iconViews.put(rp, iv);

            // 按 markTypeName 分组：惰性创建 Group 并绑定筛选状态
            String name = rp.getConfig().getMarkTypeName();
            Group nameGroup = nameGroups.computeIfAbsent(name, _ -> {
                Group g = new Group();
                g.setPickOnBounds(false);
                g.setMouseTransparent(true);
                g.visibleProperty().bind(app.getResourceFilter(name));
                iconGroup.getChildren().add(g);
                return g;
            });
            nameGroup.getChildren().add(iv);
            built++;
        }

        log.info("图标 ImageView 层已构建: {} 个点位, {} 个名称分组", built, nameGroups.size());
    }

    /**
     * 检测玩家附近新变灰的资源点，直接切换 ImageView 的图集引用。
     */
    void updateGrayStates(double playerX, double playerY) {
        // 减少检测频率：玩家移动超过阈值才检测
        double dx = playerX - lastGrayCheckX;
        double dy = playerY - lastGrayCheckY;
        if (dx * dx + dy * dy <= RenderConfig.GRAY_CHECK_THRESHOLD * RenderConfig.GRAY_CHECK_THRESHOLD) {
            return;
        }
        lastGrayCheckX = playerX;
        lastGrayCheckY = playerY;

        double r2 = ViewConfig.GRAY_DISTANCE * ViewConfig.GRAY_DISTANCE;
        IconCache cache = IconCache.getInstance();
        Image grayAtlas = cache.getGrayAtlas();
        if (grayAtlas == null) {
            log.warn("[gray] grayAtlas is null, skipping");
            return;
        }

        List<ResourcePoint> nearby = ResourcePointContext.getInstance().getNearbyResources(playerX, playerY);
        for (ResourcePoint rp : nearby) {
            if (rp.isGrayed()) continue;
            Point pos = rp.getScreenPosition();
            double pdx = pos.getX() - playerX;
            double pdy = pos.getY() - playerY;
            if (pdx * pdx + pdy * pdy > r2) continue;
            if (!ResourcePointContext.getInstance().isCollect(rp.getConfig().getMarkTypeName())) continue;

            // ⚠️ 必须先从 HashMap 获取 ImageView，再修改 grayed，
            //    否则 grayed 改变 hashCode → get() 查不到
            ImageView iv = iconViews.get(rp);
            rp.setGrayed(true);
            if (iv != null) {
                iv.setImage(grayAtlas);
            }
        }
    }
}
