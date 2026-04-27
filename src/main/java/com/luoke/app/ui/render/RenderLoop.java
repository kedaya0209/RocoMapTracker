package com.luoke.app.ui.render;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.CameraContext;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.MaterialCollectionContext;
import com.luoke.app.context.StatsContext;
import com.luoke.app.ui.component.InteractiveCanvas;
import com.luoke.app.ui.component.ResourceCounterPanel;
import com.luoke.app.ui.component.TitleBar;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.Map;

/**
 * 渲染循环类
 *
 * <p>继承自JavaFX的AnimationTimer，负责每一帧的渲染工作。
 * 该类按顺序渲染以下内容：
 * <ul>
 *   <li>1. 清空画布</li>
 *   <li>2. 渲染小地图（应用缩放和平移变换）</li>
 *   <li>3. 渲染资源点位图标</li>
 *   <li>4. 渲染统计信息UI</li>
 *   <li>5. 渲染玩家位置和朝向</li>
 * </ul>
 *
 * <p><b>性能优化要点：</b>
 * <ul>
 *   <li>使用单例模式的渲染器实例，避免重复创建对象</li>
 *   <li>使用GraphicsContext.save()/restore()保护绘图状态</li>
 *   <li>使用Text对象预先测量文本宽度，避免重复计算</li>
 *   <li>根据配置动态控制渲染内容，避免不必要的绘制</li>
 * </ul>
 *
 * <p><b>线程安全：</b>
 * <ul>
 *   <li>本类在JavaFX应用线程中执行，确保UI操作安全</li>
 *   <li>依赖的Context实例提供线程安全的数据访问</li>
 * </ul>
 */
public class RenderLoop extends AnimationTimer {

    /**
     * 顶部栏高度
     * <p>与MainApp中topBar的padding保持一致，用于统计信息UI的垂直定位。
     * 使用final确保编译时常量，提升性能。
     */
    private static final int TOP_BAR_HEIGHT = 40;

    /**
     * JavaFX绘图上下文
     * <p>绑定到特定的Canvas，用于所有绘制操作。
     * 在类构造时初始化，整个生命周期内保持不变。
     */
    private final GraphicsContext gc;

    /**
     * 字体对象
     * <p>用于统计信息UI的文本渲染。
     * 使用Microsoft YaHei字体，字号14px，确保中文显示良好。
     * 使用final避免重复创建，提升性能。
     */
    private final Font font = Font.font("Microsoft YaHei", 14);

    /**
     * 文本测量工具
     * <p>用于预先测量文本的宽度和高度，优化布局计算。
     * 避免在渲染循环中重复创建Text对象，减少GC压力。
     */
    private final Text textMeasurer;

    /**
     * 构造函数
     *
     * <p>初始化渲染循环，绑定到指定的绘图上下文。
     *
     * @param gc JavaFX绘图上下文，必须与使用的Canvas关联
     */
    public RenderLoop(GraphicsContext gc) {
        this.gc = gc;
        // 设置文本测量器的字体，确保测量结果与实际渲染一致
        textMeasurer = new Text();
        textMeasurer.setFont(font);
    }

    /**
     * 处理每一帧的渲染
     *
     * <p>由JavaFX动画框架定时调用，执行完整的渲染流程。
     * 该方法的执行频率受JavaFX脉冲机制控制（约60FPS）。
     *
     * <p><b>渲染顺序：</b>
     * <ol>
     *   <li>更新摄像机视口（响应缩放和平移操作）</li>
     *   <li>清空画布（准备绘制新帧）</li>
     *   <li>渲染小地图（最底层）</li>
     *   <li>渲染资源图标（中层）</li>
     *   <li>渲染统计UI（上层，不随地图移动）</li>
     *   <li>渲染玩家位置（顶层）</li>
     * </ol>
     *
     * @param now 当前时间戳（纳秒），由AnimationTimer提供
     */
    @Override
    public void handle(long now) {
        // 更新摄像机视口，响应用户的缩放和平移操作
        CameraContext camera = CameraContext.getInstance();
        camera.updateViewport();
        if (camera.hasValidPlayerPosition()) {
            camera.updateViewport();
        }

        // 获取画布当前尺寸，用于清空和UI定位
        double canvasWidth = gc.getCanvas().getWidth();
        double canvasHeight = gc.getCanvas().getHeight();

        // 清空画布，为绘制新帧做准备
        gc.clearRect(0, 0, canvasWidth, canvasHeight);

        // 渲层1：渲染小地图背景（会应用缩放和平移变换）
        renderMap();

        // 渲层2：渲染资源点位图标（位于地图之上）
        renderResourceIcons();

        // 渲层3：渲染统计信息UI（固定在屏幕顶部，不受地图变换影响）
        renderStatsUI(canvasWidth);

        // 渲层4：渲染玩家位置和朝向（最顶层）
        renderPlayer();

        //渲染拾取物
        rendResourceCount();
    }

    /**
     * 渲染小地图
     *
     * <p>将小地图图像绘制到画布上，应用摄像机变换（缩放和平移）。
     * 使用save()/restore()确保变换不会影响后续绘制操作。
     *
     * <p><b>渲染变换逻辑：</b>
     * <ul>
     *   <li>平移：根据CameraContext中的offsetX和offsetY移动原点</li>
     *   <li>缩放：根据CameraContext中的scale进行等比例缩放</li>
     *   <li>原点：左上角为(0,0)</li>
     * </ul>
     *
     * <p><b>性能考虑：</b>
     * <ul>
     *   <li>如果地图未加载，提前返回避免空指针</li>
     *   <li>使用save()/restore()保护绘图状态栈，避免状态污染</li>
     * </ul>
     */
    private void renderMap() {
        // 获取单例MapContext，访问地图数据和摄像机状态
        MapContext mm = MapContext.getInstance();

        // 如果地图图像未加载，跳过渲染
        if (mm.getMapImage() == null) return;

        // 保存当前绘图状态（坐标变换、剪裁、样式等）
        gc.save();

        // 应用平移变换：移动原点到摄像机位置
        gc.translate(mm.getOffsetX(), mm.getOffsetY());

        // 应用缩放变换：支持地图缩放功能
        gc.scale(mm.getScale(), mm.getScale());

        // 绘制地图图像（在变换后的坐标系中）
        gc.drawImage(mm.getMapImage(), 0, 0);

        // 恢复绘图状态，确保不影响后续绘制
        gc.restore();
    }

    /**
     * 渲染玩家位置和朝向
     *
     * <p>根据配置的渲染模式选择不同的渲染器：
     * <ul>
     *   <li>simulation模式：使用PlayerRenderer（基于OCR识别的朝向）</li>
     *   <li>cutter模式：使用CutterPlayerRenderer（直接裁剪裁剪中心的圆形区域）</li>
     * </ul>
     *
     * <p><b>设计意图：</b>
     * <ul>
     *   <li>使用策略模式，根据配置动态选择渲染算法</li>
     *   <li>单例模式管理渲染器实例，避免重复创建</li>
     *   <li>渲染器本身负责所有绘制逻辑，保持代码职责分离</li>
     * </ul>
     */
    private void renderPlayer() {
        // 检查配置的渲染模式
        if ("simulation".equalsIgnoreCase(AppConfig.PLAYER_ICON_MODEL)) {
            // simulation模式：使用PlayerRenderer
            // 该模式通过OCR识别裁剪中心的箭头图标，获取精确朝向
            PlayerRenderer.getInstance().draw(gc);
            return;
        }
        // cutter模式：使用CutterPlayerRenderer
        // 该模式直接从裁剪中心裁剪圆形区域显示，性能更高但朝向精度较低
        CutterPlayerRenderer.getInstance().draw(gc);
    }

    /**
     * 渲染所有资源点位图标
     *
     * <p>调用InteractiveCanvas的绘制方法，将资源图标绘制到画布上。
     * 资源图标包括：玩家标记、任务点、道具等。
     *
     * <p><b>渲染优化：</b>
     * <ul>
     *   <li>使用视图剪裁（Viewport Culling）优化：只绘制屏幕范围内的图标</li>
     *   <li>图层管理：确保资源图标在小地图之上、玩家标记之下</li>
     * </ul>
     *
     * <p><b>设计意图：</b>
     * <ul>
     *   <li>将资源绘制逻辑委托给InteractiveCanvas，保持RenderLoop职责单一</li>
     *   <li>使用模式匹配（instanceof）简化类型检查和转换</li>
     * </ul>
     */
    private void renderResourceIcons() {
        // 检查Canvas是否为InteractiveCanvas实例
        // 只有InteractiveCanvas才具备资源图标绘制能力
        if (!(gc.getCanvas() instanceof InteractiveCanvas canvas)) {
            return;
        }
        // 委托给InteractiveCanvas执行资源图标绘制
        // 这样可以将渲染逻辑分散到各组件中，保持代码结构清晰
        canvas.drawAllResourceIcons(gc);
    }

    /**
     * 渲染统计信息UI
     *
     * <p>在画布右上角显示性能和调试统计信息，包括：
     * <ul>
     *   <li>小地图检测耗时（毫秒）</li>
     *   <li>特征匹配耗时（毫秒）</li>
     *   <li>朝向计算耗时（毫秒）</li>
     *   <li>渲染频率（FPS）</li>
     * </ul>
     *
     * <p><b>布局定位：</b>
     * <ul>
     *   <li>水平方向：右对齐，距离右边15px</li>
     *   <li>垂直方向：垂直居中于顶部栏（高度40px）</li>
     *   <li>文本颜色：黑色</li>
     * </ul>
     *
     * <p><b>性能优化：</b>
     * <ul>
     *   <li>使用StringBuilder动态拼接文本，避免频繁字符串操作</li>
     *   <li>根据配置开关控制显示内容，避免不必要的计算</li>
     *   <li>预先使用Text对象测量文本尺寸，优化布局计算</li>
     * </ul>
     *
     * @param canvasWidth 画布宽度，用于计算文本的右对齐位置
     */
    private void renderStatsUI(double canvasWidth) {
        // 获取统计上下文单例，访问性能数据
        StatsContext stats = StatsContext.getInstance();
        // 设置字体，确保文本测量和实际渲染一致
        gc.setFont(font);

        // 使用StringBuilder动态拼接统计文本
        // 比String的+操作更高效，减少内存分配
        StringBuilder sb = new StringBuilder();

        // 根据配置动态拼接统计信息
        // 这种设计允许用户只关心他们想看的指标，提升灵活性
        if (AppConfig.SHOW_STATS_MAP_TIME) {
            sb.append(String.format("小地图：%dms ", stats.getLastMapDetectMs()));
        }
        if (AppConfig.SHOW_STATS_MATCH_TIME) {
            sb.append(String.format("匹配：%dms ", stats.getLastMatchMs()));
        }
        if (AppConfig.SHOW_STATS_DIR_TIME) {
            sb.append(String.format("朝向：%dms ", stats.getLastDirectionMs()));
        }
        if (AppConfig.SHOW_STATS_FPS) {
            sb.append(String.format("频率：%d", stats.getFrequency()));
        }
        // 提取最终文本
        String text = sb.toString().trim();
        // 如果没有启用任何统计显示，提前返回
        if (text.isBlank()) return;

        // 使用textMeasurer测量文本尺寸
        // 这是JavaFX中测量文本的标准方式，比直接计算更准确
        textMeasurer.setText(text);
        double textWidth = textMeasurer.getLayoutBounds().getWidth();
        double textHeight = textMeasurer.getLayoutBounds().getHeight();

        // 定义文本边距
        double margin = 15;

        // 计算垂直居中位置
        // 文本基线位于垂直中心下方约4px处（这是JavaFX文本渲染的特性）
        double bgY = (TOP_BAR_HEIGHT - textHeight) / 2;
        // 计算右对齐位置：画布宽度 - 文本宽度 - 边距
        double textX = canvasWidth - textWidth - margin;

        TitleBar instance = TitleBar.getInstance();
        double offsetY = instance.getHeight();
        // 计算文本Y坐标：背景Y + 文本高度/2 + 基线偏移
        double textY = bgY + textHeight / 2 + 4 + offsetY;

        // 设置文本颜色为黑色
        gc.setFill(Color.BLACK);
        // 绘制统计文本
        gc.fillText(text, textX, textY);
    }

    private void rendResourceCount() {
        // 1. 获取单例实例
        ResourceCounterPanel resourcePanel = ResourceCounterPanel.getInstance();
        MaterialCollectionContext collectionContext = MaterialCollectionContext.getInstance();

        // 2. 获取当前的汇总数据视图
        Map<String, Integer> summaryMap = collectionContext.getSummaryMap();

        // 3. UI 更新必须在 JavaFX 主线程执行
        // 4. 清理旧数据行：保留索引 0 的标题 Label，移除之后的所有内容
        int childCount = resourcePanel.getChildren().size();
        if (childCount > 1) {
            resourcePanel.getChildren().remove(1, childCount);
        }

        // 5. 动态构建物资列表
        summaryMap.forEach((name, total) -> {
            // 创建水平对齐的行
            javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            // 名称 Label：淡灰色
            Label nameLabel = new Label(name + " :");
            nameLabel.setTextFill(javafx.scene.paint.Color.web("#CCCCCC"));

            // 数量 Label：统一蓝色，加粗显示
            Label countLabel = new Label(String.valueOf(total));
            countLabel.setTextFill(javafx.scene.paint.Color.web("#00BFFF")); // 之前定义的 UNIFIED_BLUE
            countLabel.getStyleClass().add(atlantafx.base.theme.Styles.TEXT_BOLD);

            row.getChildren().addAll(nameLabel, countLabel);
            resourcePanel.getChildren().add(row);
        });

        // 6. 交互逻辑：如果有了第一条数据，自动淡入显示面板
        if (!summaryMap.isEmpty() && !resourcePanel.isVisible()) {
            resourcePanel.toggle(true);
        }
    }

}