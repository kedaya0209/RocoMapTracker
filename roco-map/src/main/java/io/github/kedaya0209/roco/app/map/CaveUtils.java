package io.github.kedaya0209.roco.app.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 洞穴处理共享工具 — 提供 CaveImageFuser / CaveRegionExtractor 共用的常量和辅助方法。
 */
final class CaveUtils {

    static final String MAIN_MAP = "卡洛西亚大陆.png";

    /**
     * 列出 mapsDir 下所有非大陆、非中间产物的洞穴 PNG。
     */
    static List<Path> findCavePngs(Path mapsDir) throws IOException {
        List<Path> cavePngs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(mapsDir)) {
            stream.filter(p -> p.toString().endsWith(".png")
                            && !p.getFileName().toString().equals(MAIN_MAP)
                            && !p.getFileName().toString().contains("_大陆区域"))
                  .sorted().forEach(cavePngs::add);
        }
        return cavePngs;
    }

    private CaveUtils() {
    }
}
