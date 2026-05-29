package com.luoke.app.ui.service.resource;

import net.jcip.annotations.ThreadSafe;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.geometry.Bounds;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * SVG DOM 解析 + 布局 + 图标节点创建。
 */
@ThreadSafe
final class SvgIconBuilder {

    private static final Map<String, byte[]> svgCache = new ConcurrentHashMap<>();

    private SvgIconBuilder() {
    }

    static Node createIcon(String resourcePath, double size) {
        return createIcon(resourcePath, size, "-fx-fill: -color-fg-default;");
    }

    static Node createIcon(String resourcePath, double size, String style) {
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

    static Image createImage(String resourcePath, double size) {
        StackPane icon = (StackPane) createIcon(resourcePath, size);
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        WritableImage img = new WritableImage((int) size, (int) size);
        return icon.snapshot(sp, img);
    }

    static String getPath(String resourcePath) {
        try {
            byte[] raw = loadRaw(resourcePath);
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(raw));
            NodeList pathNodes = doc.getDocumentElement().getElementsByTagName("path");
            if (pathNodes.getLength() == 0) return "";
            return ((Element) pathNodes.item(0)).getAttribute("d");
        } catch (Exception e) {
            return "";
        }
    }

    static void clearCache() {
        svgCache.clear();
    }

    // ================================================================
    // 内部实现
    // ================================================================

    static byte[] loadRaw(String resourcePath) {
        return svgCache.computeIfAbsent(resourcePath, path -> {
            try (InputStream is = SvgIconBuilder.class.getResourceAsStream(path)) {
                if (is == null) throw new RuntimeException("SVG not found: " + path);
                return is.readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read SVG: " + path, e);
            }
        });
    }

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

    static Group buildBaseGroup(byte[] raw, double size) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(raw));
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

            String fill = el.getAttribute("fill");
            if (!fill.isEmpty() && !"none".equals(fill)) {
                String fillOpacityStr = el.getAttribute("fill-opacity");
                double fillOpacity = 1.0;
                if (!fillOpacityStr.isEmpty()) {
                    fillOpacity = Double.parseDouble(fillOpacityStr);
                }
                sp.setFill(Color.web(fill, fillOpacity));
            }

            paths[i] = sp;

            Bounds b = sp.getBoundsInLocal();
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

    static List<SVGPath> collectPaths(Group group) {
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
}
