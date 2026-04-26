package com.luoke.app.macher.map;

import java.awt.image.BufferedImage;

/**
 * 地图匹配器接口
 *
 * <p>定义了地图匹配的核心操作，用于在大型地图中定位小图的位置。</p>
 * <p>该接口为不同的匹配算法（如SIFT、ORB等）提供了统一的API。</p>
 *
 * <h3>功能说明：</h3>
 * <ul>
 *   <li>初始化：预加载大图特征，提高后续匹配速度</li>
 *   <li>匹配：支持多种输入格式（文件路径、字节数组、BufferedImage）</li>
 *   <li>资源管理：提供显式的资源释放方法</li>
 * </ul>
 *
 * <h3>坐标返回说明：</h3>
 * <ul>
 *   <li>返回4个坐标点，格式为 double[4][2]</li>
 *   <li>每个坐标点包含 [x, y] 两个值</li>
 *   <li>坐标顺序通常为：左上、左下、右下、右上</li>
 *   <li>坐标是基于原始大图尺寸的像素坐标</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * MapMatcher matcher = new SiftMapMatcher();
 * matcher.init("/path/to/large_map.png");  // 初始化大图
 *
 * // 方式1：从文件匹配
 * double[][] corners = matcher.match("/path/to/small_image.png");
 *
 * // 方式2：从字节数组匹配（BGRA格式）
 * double[][] corners = matcher.match(bgraBytes, width, height);
 *
 * // 方式3：从BufferedImage匹配
 * double[][] corners = matcher.match(bufferedImage);
 *
 * matcher.destroy();  // 使用完毕后必须释放资源
 * }</pre>
 *
 * @author 可达鸭
 * @version 1.0
 * @see SiftMapMatcher SIFT算法实现
 */
public interface MapMatcher {

    /**
     * 初始化匹配器
     *
     * <p>该方法用于预加载大图的特征数据，通常在应用启动时调用一次。
     * 预加载可以避免每次匹配时重复提取大图特征，大幅提升性能。</p>
     *
     * <h3>实现要求：</h3>
     * <ul>
     *   <li>提取大图的特征点（如SIFT关键点）和特征描述符</li>
     *   <li>将特征数据缓存在内存中，供后续匹配使用</li>
     *   <li>可选：将特征数据保存到文件，下次直接加载</li>
     * </ul>
     *
     * <h3>调用时机：</h3>
     * <ul>
     *   <li>应用启动时，在创建匹配器实例后立即调用</li>
     *   <li>大图路径变化时，需要重新初始化</li>
     *   <li>只调用一次即可，后续匹配操作会复用缓存的特征</li>
     * </ul>
     *
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>该方法可能会耗时较长（首次需要提取特征）</li>
     *   <li>建议在后台线程中调用</li>
     * *   <li>调用前请确保大图文件存在且可读</li>
     * </ul>
     *
     * @param largeMapPath 大图的资源路径，支持classpath和外部文件
     *                   例如："/maps/large_map.png" 或 "C:/maps/large_map.png"
     */
    void init(String largeMapPath);

    /**
     * 执行匹配（文件路径版本）
     *
     * <p>从指定路径加载小图，提取特征后与大图进行匹配，返回小图在大图中的4个角点坐标。</p>
     *
     * <h3>匹配流程：</h3>
     * <ol>
     *   <li>读取小图文件</li>
     *   <li>提取小图的特征点和描述符</li>
     *   <li>与大图特征进行匹配</li>
     *   <li>计算单应性矩阵</li>
     *   <li>返回4个角点坐标</li>
     * </ol>
     *
     * <h3>返回值说明：</h3>
     * <ul>
     *   <li>成功：返回4个坐标点，格式为 double[4][2]</li>
     *   <li>失败：返回 null（原因：未初始化、特征点不足、匹配失败等）</li>
     * </ul>
     *
     * <h3>坐标说明：</h3>
     * <ul>
     *   <li>坐标顺序通常为：左上、左下、右下、右上</li>
     *   <li>坐标是基于原始大图尺寸的像素坐标</li>
     *   <li>坐标值可能存在小的误差（±1-2像素）</li>
     * </ul>
     *
     * @param smallImgPath 待定位的小图路径
     *                    例如："/maps/small_view.png"
     * @return 匹配到的4个角点坐标数组，失败返回 null
     */
    double[][] match(String smallImgPath);

    /**
     * 执行匹配（字节数组版本）
     *
     * <p>直接从BGRA格式的像素字节数组进行匹配，适用于屏幕截图、视频帧等实时图像源。</p>
     *
     * <h3>格式说明：</h3>
     * <ul>
     *   <li>像素格式：BGRA（蓝、绿、红、透明度）</li>
     *   <li>每个像素占4字节</li>
     *   <li>数据顺序：从左到右、从上到下</li>
     *   <li>数组长度应为：width * height * 4</li>
     * </ul>
     *
     * <h3>性能优势：</h3>

     * <ul>
     *   <li>无需文件I/O，速度更快</li>
     *   <li>适用于实时场景（如屏幕截图）</li>
     *   <li>避免图像格式转换开销</li>
     * </ul>
     *
     * @param imageBytes BGRA格式的像素字节数组
     *                  数组长度必须等于 width * height * 4
     * @param width 图像宽度（像素）
     * @param height 图像高度（像素）
     * @return 匹配到的4个角点坐标数组，失败返回 null
     */
    double[][] match(byte[] imageBytes, int width, int height);

    /**
     * 执行匹配（BufferedImage版本）
     *
     * <p>从Java AWT的BufferedImage对象进行匹配，适用于AWT/Swing等Java标准库生成的图像。</p>
     *
     * <h3>使用场景：</h3>
     * <ul>
     *   <li>AWT/Swing应用中的图像组件</li>
     *   <li>通过ImageIO读取的图像文件</li>
     *   <li>Java标准库生成的图像</li>
     * </ul>
     *
     * <h3>性能考虑：</h3>
     * <ul>
     *   <li>需要经过多次转换：BufferedImage → Frame → Mat</li>
     *   <li>转换过程可能产生额外的Native对象</li>
     *   <li>性能可能略低于字节数组版本</li>
     *   <li>建议优先使用字节数组版本以获得更好的性能</li>
     * </ul>
     *
     * @param image 待匹配的BufferedImage对象
     *              支持RGB、BGR、BGRA等常见格式
     * @return {4][2] 匹配到的4个角点坐标数组，失败返回 null
     */
    double[][] match(BufferedImage image);

    /**
     * 释放持久化资源
     *
     * <p>释放匹配器中持有的Native资源，包括缓存的特征数据、算法对象等。
     * 在Native Image环境中，显式释放Native资源尤为重要，否则会导致内存泄漏。</p>
     *
     * <h3>调用时机：</h3>
     * <ul>
     *   <li>应用退出前</li>
     *   <li>不再需要匹配器时</li>
     *   <li>准备重新初始化前</li>
     * </ul>
     *
     * <h3>释放的资源：</h3>
     * <ul>
     *   <li>大图特征描述符矩阵</li>
     *   <li>大图特征点向量</li>
     *   <li>特征提取器（如SIFT）</li>
     *   <li>匹配器（如FLANN）</li>
     *   <li>其他Native资源对象</li>
     * </ul>
     *
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>调用后此匹配器实例将不可用</li>
     *   <li>如需再次使用，需要重新创建实例并初始化</li>
     *   <li>建议在finally块中调用，确保资源被释放</li>
     * </ul>
     */
    void destroy();
}
