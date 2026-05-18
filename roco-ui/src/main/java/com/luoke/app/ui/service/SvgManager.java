package com.luoke.app.ui.service;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.util.Duration;

import javax.xml.parsers.DocumentBuilderFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * SVG 路径管理器 — 从 classpath 资源文件加载 SVG path 数据。
 * 提供缓存、图标节点创建、路径长度计算和 hover 画线动画。
 *
 * <p>SVG 文件需放在资源目录（如 src/main/resources/icon/），
 * 通过 classpath 路径引用：{@code /icon/filename.svg}。</p>
 *
 * <p>内部使用 XML DOM 解析 + {@link Group} 包裹变换后的 {@link SVGPath} 节点，
 * Group 的 layoutBounds 包含子节点变换后尺寸，可安全放入 StackPane 等容器。</p>
 */
public class SvgManager {

    /**
     * SVG 文档缓存（按资源路径）
     */
    private static final Map<String, byte[]> svgCache = new ConcurrentHashMap<>();

    /**
     * 路径长度缓存（按 d 属性字符串），避免重复解析
     */
    private static final Map<String, Double> pathLengthCache = new ConcurrentHashMap<>();

    /**
     * SVG path 指令与数字的 token 正则
     */
    private static final Pattern SVG_TOKEN = Pattern.compile(
            "[MLCQAZHVSTmlcqazhvst]|[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?");

    // ================================================================
    // 公开方法
    // ================================================================

    /**
     * 创建固定尺寸的 SVG 图标节点（矢量，可直接用作按钮图形）。
     * 返回一个 {@code size × size} 的 StackPane，内部包含经过缩放居中变换的 SVGPath。
     *
     * <p>样式使用 {@code -fx-fill: -color-fg-default;} 以适配当前主题。</p>
     */
    public static Node createIcon(String resourcePath, double size) {
        return createIcon(resourcePath, size, "-fx-fill: -color-fg-default;");
    }

    /**
     * 创建固定尺寸的 SVG 图标节点，允许指定样式。
     *
     * @param resourcePath SVG 资源 classpath 路径
     * @param size         图标尺寸（宽高）
     * @param style        应用到所有 SVGPath 的 CSS 样式，为 null 时保留 SVG 原始 fill 属性
     * @return 包含 SVG 的 StackPane 节点
     */
    public static Node createIcon(String resourcePath, double size, String style) {
        StackPane box = new StackPane();
        box.setPrefSize(size, size);
        box.setMinSize(size, size);
        box.setMaxSize(size, size);

        try {
            byte[] raw = loadRaw(resourcePath);
            Group group = buildGroup(raw, size, style);
            box.getChildren().add(group);
        } catch (Exception e) {
            // 失败时返回空容器
        }
        return box;
    }

    /**
     * 创建 hover 画线动画图标。
     * 鼠标进入时填充透明、描边从无到有逐笔画出，移出时反向擦除。
     * 多个 {@code <path>} 元素同时播放动画。
     *
     * @param resourcePath   SVG 资源 classpath 路径
     * @param size           图标尺寸（宽高）
     * @param strokeColor    描边颜色（也用作正常态填充色）
     * @param strokeWidth    描边宽度
     * @param durationMillis 单次画线/擦除动画时长（毫秒）
     * @return 包含 hover 动画的 StackPane 节点
     */
    public static Node createHoverDrawIcon(
            String resourcePath, double size,
            Color strokeColor, double strokeWidth, int durationMillis) {

        StackPane box = new StackPane();
        box.setPrefSize(size, size);
        box.setMinSize(size, size);
        box.setMaxSize(size, size);

        Group group;
        try {
            byte[] raw = loadRaw(resourcePath);
            group = buildBaseGroup(raw, size);
            group.setMouseTransparent(true);
            List<SVGPath> paths = collectPaths(group);

            // 正常态显示填充色图标，hover 时由 animatePaths 动态添加描边
            for (SVGPath p : paths) {
                p.setFill(strokeColor);
            }
            group.getProperties().put("_adjStrokeWidth", strokeWidth);

            box.getChildren().add(group);
        } catch (Exception e) {
            return box;
        }

        box.setPickOnBounds(true);
        Group animGroup = group;
        box.setOnMouseEntered(_ -> animatePaths(animGroup, true, durationMillis));
        box.setOnMouseExited(_ -> animatePaths(animGroup, false, durationMillis));

        return box;
    }

    /**
     * 创建 hover 画线动画图标（CSS 主题色版）。
     * 正常显示填充色图标，hover 时填充透明、描边沿路径从无到有勾勒，
     * 画完后恢复填充图标状态。
     *
     * @param resourcePath   SVG 资源 classpath 路径
     * @param size           图标尺寸（宽高）
     * @param strokeWidth    hover 画线描边宽度
     * @param durationMillis 单次画线/擦除动画时长（毫秒）
     * @return 包含 hover 动画的 StackPane 节点
     */
    public static Node createHoverDrawIcon(
            String resourcePath, double size,
            double strokeWidth, int durationMillis) {
        StackPane box = new StackPane();
        box.setPrefSize(size, size);
        box.setMinSize(size, size);
        box.setMaxSize(size, size);

        try {
            byte[] raw = loadRaw(resourcePath);
            Group group = buildBaseGroup(raw, size);
            group.setMouseTransparent(true);

            // 从 transforms 提取缩放因子，计算补偿后的 strokeWidth
            List<SVGPath> allPaths = collectPaths(group);
            double scale = 1.0;
            if (!allPaths.isEmpty()) {
                for (javafx.scene.transform.Transform t : allPaths.getFirst().getTransforms()) {
                    if (t instanceof javafx.scene.transform.Scale s) {
                        scale = s.getX();
                        break;
                    }
                }
                if (scale == 0) scale = 1.0;
            }
            double adjStrokeWidth = strokeWidth / scale;
            group.getProperties().put("_adjStrokeWidth", adjStrokeWidth);

            // 仅设置填充（CSS 主题色），不设置 stroke（动画时动态添加）
            for (SVGPath p : allPaths) {
                p.setStyle("-fx-fill: -color-fg-default;");
            }
            box.getChildren().add(group);

            // 鼠标事件
            box.setPickOnBounds(true);
            box.setOnMouseEntered(_ -> animatePaths(group, true, durationMillis));
            box.setOnMouseExited(_ -> animatePaths(group, false, durationMillis));

            // 入 scene 后修正为真实主题色
            deferredColorUpdate(box, group);
        } catch (Exception e) {
            return box;
        }

        return box;
    }

    /**
     * 节点入 scene 后解析 fill/accent 为 CSS 主题色，存入 group 属性供动画使用
     */
    private static void deferredColorUpdate(StackPane box, Group group) {
        Runnable task = () -> {
            if (box.getScene() == null) return;
            Color fillColor = resolveCssColor(group, "-color-fg-default");
            if (fillColor == null) fillColor = Color.web("#24292f");
            Color accent = resolveCssColor(group, "-color-accent-emphasis");
            if (accent == null) accent = Color.web("#0969da");
            // 不设置 SVGPath fill——CSS 内联样式 -fx-fill: -color-fg-default 已处理
            group.getProperties().put("_fgFill", fillColor);
            group.getProperties().put("_accent", accent);
        };
        if (box.getScene() != null) {
            Platform.runLater(task);
        } else {
            box.sceneProperty().addListener(new ChangeListener<Scene>() {
                @Override
                public void changed(ObservableValue<? extends Scene> obs,
                                    Scene oldScene, Scene newScene) {
                    if (newScene != null) {
                        box.sceneProperty().removeListener(this);
                        Platform.runLater(task);
                    }
                }
            });
        }
    }

    /**
     * 向 Group 添加临时 Rectangle 并强制 CSS 处理，解析 CSS 变量颜色
     */
    private static Color resolveCssColor(Group group, String cssVariable) {
        try {
            Rectangle tmp = new Rectangle();
            tmp.setStyle("-fx-fill: " + cssVariable + ";");
            group.getChildren().add(tmp);
            // 直接处理临时节点的 CSS，不依赖父节点 impl 级联
            tmp.applyCss();
            Paint p = tmp.getFill();
            group.getChildren().remove(tmp);
            return p instanceof Color c ? c : null;
        } catch (Exception e) {
            return null;
        }
    }


    /**
     * 外部控制 hover 画线动画。
     * hover 进入时路径从无到有逐笔画出，离开时逐渐消失。
     *
     * @param iconNode       由 {@link #createHoverDrawIcon} 创建的 StackPane
     * @param enter          true = 进入（画线），false = 离开（擦除）
     * @param durationMillis 动画时长（毫秒）
     */
    public static void animateHoverDrawIcon(Node iconNode, boolean enter, int durationMillis) {
        if (iconNode instanceof StackPane box) {
            for (Node child : box.getChildren()) {
                if (child instanceof Group group) {
                    animatePaths(group, enter, durationMillis);
                    return;
                }
            }
        }
    }

    public static String getPath(String resourcePath) {
        try {
            byte[] raw = loadRaw(resourcePath);
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = dbf.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(raw));
            NodeList pathNodes = doc.getDocumentElement().getElementsByTagName("path");
            if (pathNodes.getLength() == 0) return "";
            return ((org.w3c.dom.Element) pathNodes.item(0)).getAttribute("d");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 计算 SVGPath 的路径总长度（精确到各命令段，曲线上采样 20 段近似）。
     * 结果被缓存，以 {@code d} 属性值为键。
     */
    public static double computePathLength(SVGPath path) {
        return pathLengthCache.computeIfAbsent(path.getContent(), SvgManager::computeLength);
    }

    /**
     * 创建固定尺寸的 SVG 图标 Image（用于程序图标/Stage 图标等需要 Image 而非 Node 的场景）。
     * 内部使用 {@link #createIcon} 构建节点，再通过 snapshot 生成 {@link Image}。
     */
    public static Image createImage(String resourcePath, double size) {
        StackPane icon = (StackPane) createIcon(resourcePath, size);
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        WritableImage img = new WritableImage((int) size, (int) size);
        return icon.snapshot(sp, img);
    }

    /**
     * 清除缓存（用于资源热更新场景）
     */
    public static void clearCache() {
        svgCache.clear();
        pathLengthCache.clear();
    }

    // ================================================================
    // 内部实现 — 图标构建
    // ================================================================

    /**
     * 缓存并读取 SVG 文件的原始字节
     */
    private static byte[] loadRaw(String resourcePath) {
        return svgCache.computeIfAbsent(resourcePath, path -> {
            try (java.io.InputStream is = SvgManager.class.getResourceAsStream(path)) {
                if (is == null) throw new RuntimeException("SVG not found: " + path);
                return is.readAllBytes();
            } catch (Exception e) {
                throw new RuntimeException("Failed to read SVG: " + path, e);
            }
        });
    }

    /**
     * 从 SVG XML 字节构建图标 Group，应用样式。
     * 内部委托 {@link #buildBaseGroup} 进行 DOM 解析与变换，再叠加样式。
     */
    private static Group buildGroup(byte[] raw, double size, String style) throws Exception {
        Group group = buildBaseGroup(raw, size);
        if (style != null) {
            for (Node node : group.getChildren()) {
                if (node instanceof SVGPath sp) {
                    sp.setStyle(style);
                }
            }
        }
        return group;
    }

    /**
     * 仅构建位置/缩放正确的 Group，不应用任何样式。
     * 解析所有 {@code <path>} 元素 → 计算包围盒 → Scale + Translate 居中缩放。
     */
    private static Group buildBaseGroup(byte[] raw, double size) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document doc = dbf.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(raw));
        Element svgRoot = doc.getDocumentElement();
        NodeList pathNodes = svgRoot.getElementsByTagName("path");

        int n = pathNodes.getLength();
        SVGPath[] paths = new SVGPath[n];
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (int i = 0; i < n; i++) {
            Element el = (Element) pathNodes.item(i);
            SVGPath sp = new SVGPath();
            sp.setContent(el.getAttribute("d"));

            // 保留原始 fill 属性（多色 SVG 需要），buildGroup 传入 style 为 null 时可跳过样式覆盖
            String fill = el.getAttribute("fill");
            if (!fill.isEmpty() && !"none".equals(fill)) {
                String fillOpacityStr = el.getAttribute("fill-opacity");
                double fillOpacity = 1.0;
                if (!fillOpacityStr.isEmpty()) {
                    fillOpacity = Double.parseDouble(fillOpacityStr);
                }
                sp.setFill(javafx.scene.paint.Color.web(fill, fillOpacity));
            }

            paths[i] = sp;

            javafx.geometry.Bounds b = sp.getBoundsInLocal();
            minX = Math.min(minX, b.getMinX());
            minY = Math.min(minY, b.getMinY());
            maxX = Math.max(maxX, b.getMaxX());
            maxY = Math.max(maxY, b.getMaxY());
        }

        double pw = maxX - minX;
        double ph = maxY - minY;
        double scale = size / Math.max(pw, ph);
        double tx = -minX * scale + (size - pw * scale) / 2;
        double ty = -minY * scale + (size - ph * scale) / 2;

        Group group = new Group();
        for (SVGPath path : paths) {
            path.getTransforms().add(new Scale(scale, scale));
            path.getTransforms().add(new Translate(tx, ty));
        }
        group.getChildren().addAll(paths);
        return group;
    }

    /**
     * 从 Group 中递归收集所有 SVGPath 节点
     */
    private static List<SVGPath> collectPaths(Group group) {
        List<SVGPath> result = new ArrayList<>();
        for (Node node : group.getChildren()) {
            if (node instanceof SVGPath sp) {
                result.add(sp);
            } else if (node instanceof Group g) {
                result.addAll(collectPaths(g));
            }
        }
        return result;
    }

    // ================================================================
    // 内部实现 — 画线动画
    // ================================================================

    /**
     * 为 Group 内所有 SVGPath 执行画线/擦除动画。
     * 如果前一个动画尚未结束会被中断，从当前位置开始新动画。
     *
     * <p>动画期间填充临时透明，描边沿路径从无到有（或反向）勾勒，
     * 动画完成后恢复填充、移除描边配置。</p>
     */
    private static void animatePaths(Group group, boolean enter, int durationMillis) {
        // 停止上次动画，恢复可能遗留的透明填充
        Timeline prev = (Timeline) group.getProperties().get("_drawAnim");
        if (prev != null) {
            prev.stop();
            restorePathsPostAnim(group);
        }

        List<SVGPath> paths = collectPaths(group);
        if (paths.isEmpty()) return;

        // 使用填充色作为描边颜色（主题默认文字色，与图标正常态一致）
        Color strokeColor = (Color) group.getProperties().get("_fgFill");
        if (strokeColor == null) {
            // Color 版本降级到已有的 stroke 或 fill
            Paint s = paths.getFirst().getStroke();
            if (s instanceof Color c) strokeColor = c;
            if (strokeColor == null) {
                Paint f = paths.getFirst().getFill();
                if (f instanceof Color c) strokeColor = c;
            }
        }
        if (strokeColor == null) strokeColor = Color.web("#24292f");

        double adjStrokeWidth = (double) group.getProperties().getOrDefault("_adjStrokeWidth", 0.0);
        if (adjStrokeWidth <= 0) {
            adjStrokeWidth = paths.getFirst().getStrokeWidth();
            if (adjStrokeWidth <= 0) adjStrokeWidth = 1.5;
        }

        for (SVGPath p : paths) {
            double len = Math.max(computePathLength(p), 10);

            // 保存当前填充，动画期间设为透明（让描边在空白上绘制，不蒙在填充上）
            p.getProperties().put("_savedFill", p.getFill());
            p.setFill(Color.TRANSPARENT);

            // 尚未配置描边则配置（使用填充色绘制，非蓝色强调色）
            if (p.getStroke() == null) {
                p.setStroke(strokeColor);
                p.setStrokeWidth(adjStrokeWidth);
                p.setStrokeLineCap(StrokeLineCap.ROUND);
                p.setStrokeLineJoin(StrokeLineJoin.ROUND);
                p.getStrokeDashArray().setAll(len, len);
            }
            p.setStrokeDashOffset(enter ? len : 0.0);
        }

        KeyValue[] kvs = new KeyValue[paths.size()];
        for (int i = 0; i < paths.size(); i++) {
            double totalLen = Math.max(computePathLength(paths.get(i)), 10);
            kvs[i] = new KeyValue(paths.get(i).strokeDashOffsetProperty(),
                    enter ? 0.0 : totalLen, Interpolator.EASE_BOTH);
        }

        Timeline tl = new Timeline(new KeyFrame(Duration.millis(durationMillis), kvs));
        tl.setOnFinished(_ -> restorePathsPostAnim(group));
        group.getProperties().put("_drawAnim", tl);
        tl.play();
    }

    /**
     * 动画结束后恢复填充颜色、清除描边配置
     */
    private static void restorePathsPostAnim(Group group) {
        for (SVGPath p : collectPaths(group)) {
            Color saved = (Color) p.getProperties().remove("_savedFill");
            if (saved != null) p.setFill(saved);
            p.setStroke(null);
            p.getStrokeDashArray().clear();
        }
    }

    // ================================================================
    // 路径长度计算器
    // ================================================================

    /**
     * 解析 SVG d 字符串并计算总长度。
     * 支持 M/L/C/Q/A/Z/H/V 及小写相对版本，贝塞尔曲线采样 20 段近似。
     */
    private static double computeLength(String d) {
        List<Object> tokens = tokenize(d);
        double[] pos = {0, 0}; // 当前画笔位置
        double[] start = {0, 0}; // 当前子路径起点
        double total = 0;
        int idx = 0;

        // 用于 S/s（平滑三次贝塞尔）和 T/t（平滑二次贝塞尔）的反射控制点追踪
        double prevCpX = 0, prevCpY = 0;
        Cmd prevCmd = null;

        while (idx < tokens.size()) {
            Object t = tokens.get(idx++);
            if (!(t instanceof Character cmdChar)) continue;

            Cmd cmd = switch (Character.toUpperCase(cmdChar)) {
                case 'M' -> Cmd.M;
                case 'L' -> Cmd.L;
                case 'C' -> Cmd.C;
                case 'Q' -> Cmd.Q;
                case 'A' -> Cmd.A;
                case 'Z' -> Cmd.Z;
                case 'H' -> Cmd.H;
                case 'V' -> Cmd.V;
                case 'S' -> Cmd.S;
                case 'T' -> Cmd.T;
                default -> throw new IllegalArgumentException("Unknown command: " + cmdChar);
            };
            boolean rel = Character.isLowerCase(cmdChar);

            if (cmd == Cmd.Z) {
                // 闭合路径：直线回到起点
                total += Math.hypot(start[0] - pos[0], start[1] - pos[1]);
                pos[0] = start[0];
                pos[1] = start[1];
                continue;
            }

            // 读取本命令对应的参数
            int argsPerSegCount = argsPerSegment(cmd);
            double[] args = readArgs(tokens, idx, argsPerSegCount, rel, pos, start, cmd);
            idx += args.length;

            if (cmd == Cmd.S || cmd == Cmd.T) {
                // 多段处理：S/T 可能包含多个坐标对
                int segLen = cmd == Cmd.S ? 4 : 2;
                for (int segStart = 0; segStart + segLen <= args.length; segStart += segLen) {
                    // 每段使用自己的反射控制点
                    boolean canReflect = (cmd == Cmd.S && (prevCmd == Cmd.C || prevCmd == Cmd.S))
                            || (cmd == Cmd.T && (prevCmd == Cmd.Q || prevCmd == Cmd.T));
                    double refX = canReflect ? 2 * pos[0] - prevCpX : pos[0];
                    double refY = canReflect ? 2 * pos[1] - prevCpY : pos[1];

                    if (cmd == Cmd.S) {
                        double x1 = rel ? pos[0] + args[segStart] : args[segStart];
                        double y1 = rel ? pos[1] + args[segStart + 1] : args[segStart + 1];
                        double x2 = rel ? pos[0] + args[segStart + 2] : args[segStart + 2];
                        double y2 = rel ? pos[1] + args[segStart + 3] : args[segStart + 3];
                        total += cubicBezierLen(pos[0], pos[1], refX, refY, x1, y1, x2, y2);
                        prevCpX = x1;
                        prevCpY = y1;
                        prevCmd = Cmd.C;
                        pos[0] = x2;
                        pos[1] = y2;
                    } else {
                        double ex = rel ? pos[0] + args[segStart] : args[segStart];
                        double ey = rel ? pos[1] + args[segStart + 1] : args[segStart + 1];
                        total += quadBezierLen(pos[0], pos[1], refX, refY, ex, ey);
                        prevCpX = refX;
                        prevCpY = refY;
                        prevCmd = Cmd.Q;
                        pos[0] = ex;
                        pos[1] = ey;
                    }
                }
                continue;
            }

            // 记录 C/Q 的控制点，供后续 S/T 反射
            if (cmd == Cmd.C) {
                // C: 控制点 2 在 args[2], args[3]（末控制点用于反射）
                prevCpX = rel ? pos[0] + args[2] : args[2];
                prevCpY = rel ? pos[1] + args[3] : args[3];
                prevCmd = Cmd.C;
            } else if (cmd == Cmd.Q) {
                prevCpX = rel ? pos[0] + args[0] : args[0];
                prevCpY = rel ? pos[1] + args[1] : args[1];
                prevCmd = Cmd.Q;
            } else if (cmd != Cmd.A) {
                prevCmd = cmd;
            }

            // 每个分段处理后更新 pos
            double consumed = processSegment(cmd, rel, args, pos, start);
            if (cmd == Cmd.M) {
                // M 后续多余坐标对视为隐式 L
                int extra = args.length - argsPerSegCount;
                for (int k = argsPerSegCount; k < args.length; k += 2) {
                    total += lineLen(rel, pos, args[k], args[k + 1]);
                }
                // pos 已在 processSegment 中更新
            } else {
                total += consumed;
            }
        }
        return total;
    }

    /**
     * 计算单个路径段的长度（M 返回 0，其余返回正数）
     */
    private static double processSegment(Cmd cmd, boolean rel, double[] args,
                                         double[] pos, double[] start) {
        double len = 0;
        // processSegment 同时负责更新 pos（画笔位置）
        switch (cmd) {
            case M -> {
                pos[0] = rel ? pos[0] + args[0] : args[0];
                pos[1] = rel ? pos[1] + args[1] : args[1];
                start[0] = pos[0];
                start[1] = pos[1];
            }
            case L -> {
                double ex = rel ? pos[0] + args[0] : args[0];
                double ey = rel ? pos[1] + args[1] : args[1];
                len = Math.hypot(ex - pos[0], ey - pos[1]);
                pos[0] = ex;
                pos[1] = ey;
            }
            case H -> {
                double ex = rel ? pos[0] + args[0] : args[0];
                len = Math.abs(ex - pos[0]);
                pos[0] = ex;
            }
            case V -> {
                double ny = rel ? pos[1] + args[0] : args[0];
                len = Math.abs(ny - pos[1]);
                pos[1] = ny;
            }
            case C -> {
                double x0 = pos[0], y0 = pos[1];
                double x1 = rel ? x0 + args[0] : args[0];
                double y1 = rel ? y0 + args[1] : args[1];
                double x2 = rel ? x0 + args[2] : args[2];
                double y2 = rel ? y0 + args[3] : args[3];
                double x3 = rel ? x0 + args[4] : args[4];
                double y3 = rel ? y0 + args[5] : args[5];
                len = cubicBezierLen(x0, y0, x1, y1, x2, y2, x3, y3);
                pos[0] = x3;
                pos[1] = y3;
            }
            case Q -> {
                double x0 = pos[0], y0 = pos[1];
                double x1 = rel ? x0 + args[0] : args[0];
                double y1 = rel ? y0 + args[1] : args[1];
                double x2 = rel ? x0 + args[2] : args[2];
                double y2 = rel ? y0 + args[3] : args[3];
                len = quadBezierLen(x0, y0, x1, y1, x2, y2);
                pos[0] = x2;
                pos[1] = y2;
            }
            case A -> {
                double x0 = pos[0], y0 = pos[1];
                double rx = Math.abs(args[0]), ry = Math.abs(args[1]);
                double xr = args[2];
                boolean laf = args[3] != 0;
                boolean sf = args[4] != 0;
                double ex = rel ? x0 + args[5] : args[5];
                double ey = rel ? y0 + args[6] : args[6];
                len = arcLen(x0, y0, rx, ry, xr, laf, sf, ex, ey);
                pos[0] = ex;
                pos[1] = ey;
            }
        }
        return len;
    }

    private static double lineLen(boolean rel, double[] pos, double x, double y) {
        double ex = rel ? pos[0] + x : x;
        double ey = rel ? pos[1] + y : y;
        double len = Math.hypot(ex - pos[0], ey - pos[1]);
        pos[0] = ex;
        pos[1] = ey;
        return len;
    }

    // ---- 几何计算 ----

    /**
     * 三次贝塞尔曲线长度（采样 20 段近似）
     */
    private static double cubicBezierLen(
            double x0, double y0,
            double x1, double y1,
            double x2, double y2,
            double x3, double y3) {
        int STEPS = 20;
        double len = 0, px = x0, py = y0;
        for (int i = 1; i <= STEPS; i++) {
            double t = (double) i / STEPS;
            double mt = 1 - t;
            double x = mt * mt * mt * x0 + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t * t * t * x3;
            double y = mt * mt * mt * y0 + 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t * t * t * y3;
            len += Math.hypot(x - px, y - py);
            px = x;
            py = y;
        }
        return len;
    }

    /**
     * 二次贝塞尔曲线长度（采样 20 段近似）
     */
    private static double quadBezierLen(
            double x0, double y0,
            double x1, double y1,
            double x2, double y2) {
        int STEPS = 20;
        double len = 0, px = x0, py = y0;
        for (int i = 1; i <= STEPS; i++) {
            double t = (double) i / STEPS;
            double mt = 1 - t;
            double x = mt * mt * x0 + 2 * mt * t * x1 + t * t * x2;
            double y = mt * mt * y0 + 2 * mt * t * y1 + t * t * y2;
            len += Math.hypot(x - px, y - py);
            px = x;
            py = y;
        }
        return len;
    }

    /**
     * 椭圆弧长度近似：转三次 Bezier + cubiBezierLen 求值
     */
    private static double arcLen(
            double x0, double y0,
            double rx, double ry, double xAxisRot,
            boolean largeArcFlag, boolean sweepFlag,
            double x, double y) {
        // 退化：半径极小或端点重合 → 直线距离
        if (rx < 0.5 || ry < 0.5) return Math.hypot(x - x0, y - y0);

        // 用 4 段三次 Bezier 近似整弧，取实际扫过的弧段
        // 简化方案：采样 16 点线性求和
        int STEPS = 16;
        double len = 0, px = x0, py = y0;
        double cosA = Math.cos(Math.toRadians(xAxisRot));
        double sinA = Math.sin(Math.toRadians(xAxisRot));

        // SVG arc 参数 → 中心点参数化（逐点采样）
        // 使用参数角 t 在 [t1, t2] 上步进
        double dx = (x0 - x) / 2, dy = (y0 - y) / 2;
        double x1p = cosA * dx + sinA * dy;
        double y1p = -sinA * dx + cosA * dy;

        double rxSq = rx * rx, rySq = ry * ry;
        double x1pSq = x1p * x1p, y1pSq = y1p * y1p;

        double cr = x1pSq / rxSq + y1pSq / rySq;
        if (cr > 1) {
            double s = Math.sqrt(cr);
            rx *= s;
            ry *= s;
            rxSq = rx * rx;
            rySq = ry * ry;
        }

        double dq = rxSq * y1pSq + rySq * x1pSq;
        if (dq < 1e-10) return Math.hypot(x - x0, y - y0);

        double sq = Math.sqrt(Math.max(0, (rxSq * rySq - dq) / dq));
        if (largeArcFlag == sweepFlag) sq = -sq;

        double cxp = sq * rx * y1p / ry;
        double cyp = -sq * ry * x1p / rx;

        double cx = cosA * cxp - sinA * cyp + (x0 + x) / 2;
        double cy = sinA * cxp + cosA * cyp + (y0 + y) / 2;

        // 计算起止角度
        double angle = (ux(x1p - cxp, y1p - cyp, rx, ry)
                + 2 * Math.PI) % (2 * Math.PI);
        double delta = (ux(-x1p - cxp, -y1p - cyp, rx, ry)
                - angle + 4 * Math.PI) % (2 * Math.PI);

        if (sweepFlag && delta > 0) delta -= 2 * Math.PI;
        if (!sweepFlag && delta < 0) delta += 2 * Math.PI;

        double deltaStep = delta / STEPS;
        for (int i = 1; i <= STEPS; i++) {
            double t = angle + i * deltaStep;
            double sinT = Math.sin(t), cosT = Math.cos(t);
            double ex = cx + rx * cosT * cosA - ry * sinT * sinA;
            double ey = cy + rx * cosT * sinA + ry * sinT * cosA;
            len += Math.hypot(ex - px, ey - py);
            px = ex;
            py = ey;
        }
        return len;
    }

    private static double ux(double x, double y, double rx, double ry) {
        if (Math.abs(x) < 1e-10 && Math.abs(y) < 1e-10) return 0;
        return Math.atan2(y / ry, x / rx);
    }

    /**
     * 将 SVG d 字符串 token 化为 Character（命令字）和 Double（数值）的列表
     */
    private static List<Object> tokenize(String d) {
        Matcher matcher = SVG_TOKEN.matcher(d);
        List<Object> tokens = new ArrayList<>();
        while (matcher.find()) {
            String raw = matcher.group();
            if (raw.length() == 1 && "MLCQAZHVSTmlcqazhvst".indexOf(raw.charAt(0)) >= 0) {
                tokens.add(raw.charAt(0));
            } else {
                tokens.add(Double.parseDouble(raw));
            }
        }
        return tokens;
    }

    // ---- tokenizer ----

    /**
     * 从 tokens 的 idx 位置连续读取 count 个数值参数
     */
    private static double[] readArgs(List<Object> tokens, int idx,
                                     int count, boolean rel, double[] pos,
                                     double[] start, Cmd cmd) {
        // 如果是 M 且后续还有坐标对，一次读完
        int total = count;
        if (cmd == Cmd.M || cmd == Cmd.L || cmd == Cmd.C || cmd == Cmd.Q || cmd == Cmd.S || cmd == Cmd.T) {
            total = readAllMatching(tokens, idx, count);
        }
        double[] args = new double[total];
        for (int i = 0; i < total; i++) {
            if (idx + i < tokens.size() && tokens.get(idx + i) instanceof Double n) {
                args[i] = n;
            } else {
                args[i] = 0;
            }
        }
        return args;
    }

    /**
     * 读取后续所有完整的坐标对，返回参数总数
     */
    private static int readAllMatching(List<Object> tokens, int idx, int coordCount) {
        int max = 0;
        for (int i = idx; i < tokens.size(); i++) {
            if (tokens.get(i) instanceof Character) break;
            max++;
        }
        // 向下取整到 coordCount 的整数倍
        return (max / coordCount) * coordCount;
    }

    /**
     * 每个命令每段的参数个数
     */
    private static int argsPerSegment(Cmd cmd) {
        return switch (cmd) {
            case M, L -> 2;
            case C -> 6;
            case Q -> 4;
            case A -> 7;
            case H, V -> 1;
            case S -> 4;
            case T -> 2;
            case Z -> 0;
        };
    }

    /**
     * SVG path 命令枚举
     */
    private enum Cmd {M, L, C, Q, A, Z, H, V, S, T}
}
