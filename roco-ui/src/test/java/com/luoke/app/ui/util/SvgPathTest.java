package com.luoke.app.ui.util;

import com.luoke.app.ui.service.SvgManager;
import javafx.scene.shape.SVGPath;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 快速测试 SvgManager 路径长度计算是否正确。
 */
public class SvgPathTest {
    public static void main(String[] args) {
        // Test paths from actual SVG files
        String[] paths = {
                // settings.svg (first path, main gear)
                "M455.111111 852.081778a352.256 352.256 0 0 0 106.723556-0.568889",
                // A simple path
                "M10 10 L100 10 L100 100 Z",
                // With S command
                "M10 10C20 20 30 20 40 10s20-10 30 0",
                // match.svg (simplified first segment)
                "M644.900571 714.532571c22.747429-11.410286 45.494857-25.014857 65.755429-36.425142",
                // theme.svg first segment
                "M85.589333 527.104l231.466667-180.053333",
        };

        for (String d : paths) {
            SVGPath p = new SVGPath();
            p.setContent(d);
            double len = SvgManager.computePathLength(p);
            System.out.println("LEN=" + len + " for: " + d.substring(0, Math.min(80, d.length())));
        }

        // Test all icon SVGs
        String[] resources = {
                "/icon/settings.svg", "/icon/match.svg", "/icon/resources.svg",
                "/icon/theme.svg", "/icon/route.svg", "/icon/ocr.svg",
                "/icon/capture.svg", "/icon/download.svg", "/icon/follow.svg",
                "/icon/ghost.svg", "/icon/minimap.svg", "/icon/motion.svg",
                "/icon/player.svg", "/icon/processor.svg", "/icon/render.svg",
                "/icon/statistics.svg"
        };

        byte[] raw;
        for (String res : resources) {
            try {
                raw = loadRaw(res);
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(raw));
                org.w3c.dom.NodeList pathNodes = doc.getDocumentElement().getElementsByTagName("path");
                double totalLen = 0;
                for (int i = 0; i < pathNodes.getLength(); i++) {
                    String d = ((org.w3c.dom.Element) pathNodes.item(i)).getAttribute("d");
                    SVGPath sp = new SVGPath();
                    sp.setContent(d);
                    double len = SvgManager.computePathLength(sp);
                    totalLen += len;
                    System.out.println("  [" + res + "] path#" + i + " len=" + len);
                }
                System.out.println("  [" + res + "] TOTAL=" + totalLen + " (" + pathNodes.getLength() + " paths)");
            } catch (Exception e) {
                System.out.println("  [" + res + "] ERROR: " + e.getMessage());
            }
        }
    }

    private static byte[] loadRaw(String path) throws Exception {
        try (var is = SvgPathTest.class.getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("Resource not found: " + path);
            return is.readAllBytes();
        }
    }
}
