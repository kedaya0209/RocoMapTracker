package com.luoke.app.map.core;

import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.entity.Tile;
import com.luoke.app.utils.FileUtil;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;

/**
 * 地图瓦片拼接器
 * <p>
 * 负责将下载的地图瓦片拼接成完整的地图图片。
 * 该类实现了以下核心功能：
 * <ul>
 *   <li>计算瓦片坐标范围，确定最终图片尺寸</li>
 *   <li>创建目标BufferedImage画布</li>
 *   <li>使用高质量渲染设置进行图片绘制</li>
 *   <li>按坐标位置将各个瓦片绘制到画布上</li>
 *   <li>输出PNG格式的完整地图图片</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>使用BufferedImage.TYPE_INT_ARGB支持透明通道</li>
 *   <li>设置高质量渲染提示，优化拼接效果</li>
 *   <li>使用双线性插值算法，提高拼接质量</li>
 *   <li>及时释放图片资源，避免内存泄漏</li>
 *   <li>支持自定义瓦片尺寸，默认为256x256</li>
 * </ul>
 * <p>
 * Native资源管理：
 * <ul>
 *   <li>BufferedImage和Graphics2D在使用后正确释放</li>
 *   <li>瓦片图片在绘制后立即flush释放内存</li>
 *   <li>主图在保存后flush释放内存</li>
 *   <li>ByteArrayInputStream使用try-with-resources自动关闭</li>
 * </ul>
 * <p>
 * 性能优化：
 * <ul>
 *   <li>预先计算所有瓦片的坐标范围</li>
 *   <li>一次性分配足够大的画布，避免动态调整</li>
 *   <li>瓦片图片使用完后立即释放，减少内存峰值</li>
 * </ul>
 *
 * @author RocoMapTracker
 * @since 1.0
 */
@Slf4j
public class MapStitcher {

    public static void stitch(List<Tile> tiles, String tag, int tw, int th) {
        try {
            // 检查瓦片列表是否为空，空则跳过拼接
            // 这种防御性编程可以避免空指针异常
            if (tiles.isEmpty()) {
                log.warn("⚠️ 地图 [{}] 无瓦片数据，跳过拼接", tag);
                return;
            }

            log.info("开始拼接地图 [{}]，有效瓦片数量：{}", tag, tiles.size());

            // 初始化坐标范围为极值，准备计算实际范围
            //X坐标的最小值和最大值
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

            // 遍历所有瓦片，计算坐标范围
            // 这是确定最终图片尺寸的关键步骤
            for (Tile t : tiles) {
                minX = Math.min(minX, t.getX());
                maxX = Math.max(maxX, t.getX());
                minY = Math.min(minY, t.getY());
                maxY = Math.max(maxY, t.getY());
            }

            // 设置瓦片尺寸，<= 0时使用默认值256
            // 这种默认值处理提高了方法的健壮性
            tw = tw > 0 ? tw : 256;
            th = th > 0 ? th : 256;
            log.info("地图 [{}] 瓦片范围 X:[{}~{}] Y:[{}~{}]，单瓦片大小：{}x{}",
                    tag, minX, maxX, minY, maxY, tw, th);

            // 计算最终图片的宽度和高度
            // 加1是因为坐标是0-based的，需要包含边界
            int width = (maxX - minX + 1) * tw;
            int height = (maxY - minY + 1) * th;
            log.info("地图 [{}] 最终生成图片尺寸：{}x{}", tag, width, height);

            // 创建目标BufferedImage画布
            // 使用TYPE_INT_ARGB支持透明通道，确保背景正确处理
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            // 创建Graphics2D绘图上下文
            // 用于将各个瓦片绘制到画布上
            Graphics2D g2d = image.createGraphics();

            // ========== 优化：高质量绘图 ==========
            // 设置渲染提示为高质量，优化拼接效果
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // 设置插值算法为双线性插值
            // 这种算法在质量和性能之间取得了良好平衡
            // 相比最近邻插值更平滑，相比双三次插值更快
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            // 遍历所有瓦片，绘制到画布对应位置
            for (Tile t : tiles) {
                // 计算瓦片在画布上的绘制位置
                // 将瓦片坐标映射到画布像素坐标
                int dx = (t.getX() - minX) * tw;
                int dy = (t.getY() - minY) * th;

                // 使用try-with-resources自动关闭ByteArrayInputStream
                // 确保资源正确释放，避免内存泄漏
                // 这对Native Image环境下的资源管理尤为重要
                try (ByteArrayInputStream bais = new ByteArrayInputStream(t.getData())) {
                    // 从字节数组读取瓦片图片
                    BufferedImage tileImg = javax.imageio.ImageIO.read(bais);
                    if (tileImg != null) {
                        // 将瓦片绘制到画布对应位置
                        g2d.drawImage(tileImg, dx, dy, null);

                        // 释放瓦片图片的Native内存
                        // 瓦片图片绘制后立即释放，减少内存峰值
                        // 这对拼接大量瓦片时避免OOM非常重要
                        tileImg.flush();
                    }
                }
            }

            // 释放Graphics2D绘图上下文
            // 释放与绘图相关的系统资源
            g2d.dispose();

            // 构建输出文件路径
            // 使用格式化字符串将tag插入到文件名中
            File outFile = FileUtil.getRelativeFile(String.format(MapResourceUpdater.OUTPUT_FILE, tag));

            // 将拼接后的图片保存为PNG格式
            // PNG格式支持无损压缩，适合地图这种需要精确显示的场景
            javax.imageio.ImageIO.write(image, "png", outFile);

            // 释放主图的Native内存
            // 图片保存后立即释放，避免内存泄漏
            // 这对Native Image环境下的资源管理至关重要
            image.flush();

            log.info("✅ 地图 [{}] 拼接完成，文件路径：{}", tag, outFile.getAbsolutePath());

        } catch (Exception e) {
            // 捕获所有异常，记录错误日志
            // 使用统一的异常处理机制，避免程序崩溃
            log.error("❌ 地图 [{}] 拼接失败", tag, e);
        }
    }
}
