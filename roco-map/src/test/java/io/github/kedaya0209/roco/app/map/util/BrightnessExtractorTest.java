package io.github.kedaya0209.roco.app.map.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * BrightnessExtractor 快速测试 — 对资源目录下的 B1、B2、G 执行亮度提取。
 */
public class BrightnessExtractorTest {

    public static void main(String[] args) throws Exception {
        String mapsDir = args.length > 0 ? args[0]
                : "D:\\Documents\\code\\Roco-tools\\resources\\source\\maps";

        String[] names = {"B1.png", "B2.png", "G.png"};

        for (String name : names) {
            Path input = Paths.get(mapsDir, name);
            Path output = Paths.get(mapsDir, name.replace(".png", "_亮度提取.png"));

            if (!input.toFile().exists()) {
                System.out.println("跳过 (不存在): " + input);
                continue;
            }

            System.out.println("处理: " + input);
            BrightnessExtractor.extractAndSave(input, output);
            System.out.println("  → 已保存: " + output);
        }

        System.out.println("全部完成");
    }
}
