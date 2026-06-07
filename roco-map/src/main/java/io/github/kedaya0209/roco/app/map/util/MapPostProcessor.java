package io.github.kedaya0209.roco.app.map.util;

import io.github.kedaya0209.roco.app.config.DownloadConfig;
import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.config.SiftConfig;
import io.github.kedaya0209.roco.app.map.LayerMapTileGenerator;
import io.github.kedaya0209.roco.app.map.core.DownloadProgressContext;
import io.github.kedaya0209.roco.app.map.MapResourceUpdater;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * WIKI 地图下载后处理器 — 生成瓦片金字塔 + MultiMap 元数据。
 * <p>
 * 在亮度提取之后、文件移入资源目录之前执行：
 * <ol>
 *   <li>扫描 {@code download/maps/map_{tag}.png}，按排序权重排列</li>
 *   <li>对大陆图直接生成瓦片；对洞穴图使用 _亮度提取.png + 半透明黑底合成后生成瓦片（不动 srcFile）</li>
 *   <li>拷贝全尺寸 PNG 到 {@code source/maps/{displayName}.png}</li>
 *   <li>生成 {@code source/maps/MultiMap_metadata.json}</li>
 * </ol>
 * <p>
 * 注意：洞穴图的 srcFile（map_{tag}.png）保留为 CaveImageFuser 输出（含大陆补充特征），
 * 专供 SIFT 训练使用。瓦片展示使用 _亮度提取.png（只含亮像素）+ 半透明黑底。
 */
@Slf4j
@ThreadSafe
public final class MapPostProcessor {

    private MapPostProcessor() {
    }

    /**
     * 执行后处理：瓦片生成 + 元数据生成 + 文件移动到外部资源目录。
     */
    public static void processMaps() throws IOException {
        String[] tags = DownloadConfig.MAP_REMOTE_URL_NAME;
        String[] displayNames = DownloadConfig.MAP_REMOTE_URL_DISPLAY_NAME;
        boolean[] isCave = DownloadConfig.MAP_REMOTE_URL_IS_CAVE;
        int[] sort = DownloadConfig.MAP_REMOTE_URL_SORT;

        if (tags.length == 0) {
            log.info("未配置 MAP_REMOTE_URL_NAME，跳过地图后处理");
            return;
        }

        // 1. 按 sort 排序索引
        List<Integer> indices = IntStream.range(0, tags.length)
                .boxed()
                .sorted(Comparator.comparingInt(i -> sort.length > i ? sort[i] : i))
                .toList();

        // 2. 创建外部目标目录 source/maps/
        File destDir = ResourceUtils.getExternalFile(PathConfig.MAPS_DIR);
        destDir.mkdirs();

        // 初始化进度跟踪
        DownloadProgressContext progress = DownloadProgressContext.getInstance();
        progress.reset("图片处理：生成图片瓦片");

        // 收集子图信息
        List<SubImageEntry> entries = new ArrayList<>();
        int offsetY = 0;

        for (int idx : indices) {
            progress.addTask();
            String tag = tags.length > idx ? tags[idx] : "unknown";
            String displayName = displayNames.length > idx && !displayNames[idx].isBlank()
                    ? displayNames[idx] : tag;
            String srcName = "map_" + tag + ".png";
            File srcFile = FilePathUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_MAP_DIR, srcName);

            if (!srcFile.exists()) {
                log.warn("地图文件不存在，跳过: {}", srcFile);
                progress.finishTask();
                continue;
            }

            File tileDir = new File(destDir, displayName);
            File destPng = new File(destDir, displayName + ".png");
            int w, h;
            boolean cave = idx < isCave.length && isCave[idx];

            if (cave) {
                // ============================================================
                // 洞穴图：使用 _亮度提取.png + 半透明黑底合成
                // srcFile（map_{tag}.png）不动，保留为 CaveImageFuser 输出供 SIFT 使用
                // ============================================================
                File brightFile = FilePathUtil.getRelativeFile(
                        MapResourceUpdater.DOWNLOAD_MAP_DIR,
                        srcName.replace(".png", "_亮度提取.png"));
                if (!brightFile.exists()) {
                    log.warn("亮度提取文件不存在，跳过: {}", brightFile);
                    progress.finishTask();
                    continue;
                }
                BufferedImage brightImg;
                try {
                    brightImg = ImageIO.read(brightFile);
                } catch (IOException e) {
                    log.warn("无法读取亮度提取文件: {}", brightFile, e);
                    progress.finishTask();
                    continue;
                }
                if (brightImg == null) {
                    log.warn("无法读取亮度提取文件: {}", brightFile);
                    progress.finishTask();
                    continue;
                }
                w = brightImg.getWidth();
                h = brightImg.getHeight();

                // 半透明黑底 + 亮度提取结果覆盖
                BufferedImage composited = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = composited.createGraphics();
                g.setColor(new Color(0, 0, 0, 178));
                g.fillRect(0, 0, w, h);
                g.drawImage(brightImg, 0, 0, null);
                g.dispose();
                brightImg.flush();

                // 写 destPng → 从 destPng 生成瓦片
                ImageIO.write(composited, "png", destPng);
                composited.flush();
                try {
                    new LayerMapTileGenerator().generateTiles(destPng.getAbsolutePath(), tileDir.getAbsolutePath());
                } catch (Exception e) {
                    log.warn("瓦片生成失败: {} (tag={})，跳过", displayName, tag, e);
                    progress.finishTask();
                    continue;
                }

            } else {
                // ============================================================
                // 大陆图：直接读取 srcFile，生成瓦片 + 拷贝 PNG
                // ============================================================
                BufferedImage img;
                try {
                    img = ImageIO.read(srcFile);
                } catch (IOException e) {
                    log.warn("无法读取地图文件: {}", srcFile, e);
                    progress.finishTask();
                    continue;
                }
                if (img == null) {
                    log.warn("无法读取地图文件: {}", srcFile);
                    progress.finishTask();
                    continue;
                }
                w = img.getWidth();
                h = img.getHeight();
                img.flush();

                // 生成瓦片
                try {
                    new LayerMapTileGenerator().generateTiles(srcFile.getAbsolutePath(), tileDir.getAbsolutePath());
                } catch (Exception e) {
                    log.warn("瓦片生成失败: {} (tag={})，跳过", displayName, tag, e);
                    progress.finishTask();
                    continue;
                }

                // 拷贝 PNG 到目标目录
                try {
                    Files.copy(srcFile.toPath(), destPng.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    log.warn("拷贝 PNG 失败: {}", srcFile, e);
                    progress.finishTask();
                    continue;
                }
            }

            entries.add(new SubImageEntry(
                    entries.size(), // index
                    displayName,
                    cave,
                    offsetY,
                    w, h,
                    "/source/maps/" + displayName + ".png",
                    "/source/maps/" + displayName + "/"
            ));
            offsetY += h;
            progress.finishTask();

            log.info("已处理: {} ({}x{}, isCave={})", displayName, w, h, cave);
        }

        if (entries.isEmpty()) {
            log.warn("没有成功处理的子图，跳过元数据生成");
            return;
        }

        // 3. 生成 MultiMap_metadata.json
        int compositeWidth = entries.get(0).width;
        int compositeHeight = entries.stream().mapToInt(e -> e.height).sum();
        generateMetadata(destDir, compositeWidth, compositeHeight, entries);

        log.info("多地图后处理完成: {} 个子图, composite={}x{}, 目录: {}",
                entries.size(), compositeWidth, compositeHeight, destDir);
    }

    /**
     * 生成 MultiMap_metadata.json。
     */
    private static void generateMetadata(File destDir, int compositeWidth, int compositeHeight,
                                         List<SubImageEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"compositeWidth\": ").append(compositeWidth).append(",\n");
        sb.append("  \"compositeHeight\": ").append(compositeHeight).append(",\n");
        sb.append("  \"matchingSift\": {\n");
        sb.append("    \"contrastThreshold\": ").append(SiftConfig.SIFT_CONTRAST_THRESHOLD).append(",\n");
        sb.append("    \"edgeThreshold\": ").append(SiftConfig.SIFT_EDGE_THRESHOLD).append(",\n");
        sb.append("    \"nfeatures\": ").append(SiftConfig.SIFT_N_FEATURES).append(",\n");
        sb.append("    \"nOctaveLayers\": ").append(SiftConfig.SIFT_N_OCTAVE_LAYERS).append(",\n");
        sb.append("    \"sigma\": ").append(SiftConfig.SIFT_SIGMA).append("\n");
        sb.append("  },\n");
        sb.append("  \"subImages\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            SubImageEntry e = entries.get(i);
            sb.append("    {\n");
            sb.append("      \"index\": ").append(e.index).append(",\n");
            sb.append("      \"name\": \"").append(e.name).append("\",\n");
            sb.append("      \"isCave\": ").append(e.isCave).append(",\n");
            sb.append("      \"offsetY\": ").append(e.offsetY).append(",\n");
            sb.append("      \"width\": ").append(e.width).append(",\n");
            sb.append("      \"height\": ").append(e.height).append(",\n");
            sb.append("      \"sourcePath\": \"").append(e.sourcePath).append("\",\n");
            sb.append("      \"tileDir\": \"").append(e.tileDir).append("\"\n");
            sb.append("    }");
            if (i < entries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        File metaFile = new File(destDir, "MultiMap_metadata.json");
        Files.writeString(metaFile.toPath(), sb.toString());
        log.info("MultiMap 元数据已写入: {}", metaFile);
    }

    private record SubImageEntry(int index, String name, boolean isCave, int offsetY,
                                 int width, int height, String sourcePath, String tileDir) {
    }
}
