package com.luoke.app.capture.jna;

import com.sun.jna.Pointer;

/**
 * 帧数据封装类
 * <p>
 * 该类使用record定义，封装了从Native层获取的帧数据信息。
 * 核心功能包括：
 * <ul>
 *   <li>存储帧数据的Native内存指针</li>
   *   <li>存储帧的宽度和高度信息</li>
   *   <li>存储帧的pitch（每行字节数）信息</li>
 *   <li>提供像素数据的读取方法</li>
 * </ul>
 *
 * <h3>内存管理</h3>
 * <ul>
 *   <li>data指针指向Native内存，由Native层管理</li>
 *   <li>不要尝试在Java层释放该指针</li>
 *   <li>帧数据在回调后可能被Native层回收</li>
 *   <li>getPixels()方法会复制数据到Java数组</li>
 * </ul>
 *
 * <h3>数据格式</h3>
 * <ul>
 *   <li>像素格式：BGRA（蓝、绿、红、透明度）</li>
 *   <li>每个像素4字节</li>
 *   <li>pitch考虑了内存对齐，可能大于width*4</li>
 * </ul>
 *
 * <h3>性能优化</h3>
 * <ul>
 *   <li>record类减少了样板代码</li>
 *   <li>getPixels()按需复制，避免不必要的内存分配</li>
   *   <li>使用pitch正确处理内存对齐</li>
 * </ul>
 *
 * @param data Native内存指针，指向帧数据
 * @param width 帧宽度（像素）
 * @param height 帧高度（像素）
 * @param pitch 每行字节数（考虑内存对齐）
 * @author RocoMapTracker Team
 * @since 1.0
 */
public record Frame(Pointer data, int width, int height, int pitch) {

    /**
     * 获取帧的像素数据
     * <p>
     * 该方法将Native内存中的帧数据复制到Java字节数组中：
     * <ol>
     *   <li>创建目标数组，大小为width*height*4</li>
     *   <li>逐行读取Native内存中的数据</li>
     *   <li>考虑pitch（内存对齐）正确计算偏移</li>
     *   <li>将数据复制到目标数组中</li>
     * </ol>
     *
     * <h3>内存对齐处理</h3>
     * <ul>
     *   <li>pitch考虑了GPU内存对齐要求</li>
     *   <li>pitch可能大于width*4</li>
     *   <li>每行之间的间距由pitch决定</li>
     * </ul>
     *
     * <h3>数据格式</h3>
     * <ul>
     *   <li>返回数组按行优先顺序排列</li>
     *   <li>每个像素4字节（BGRA）</li>
     *   <li>数组长度为width*height*4</li>
     * </ul>
     *
     * <h3>性能考虑</h3>
     * <ul>
     *   <li>每次调用都会复制整个帧数据</li>
     *   <li>对于高分辨率帧，建议缓存结果</li>
     *   <li>避免重复调用该方法</li>
     * </ul>
     *
     * <h3>使用示例</h3>
     * <pre>
     * Frame frame = ...;
     * byte[] pixels = frame.getPixels();
     * // pixels[0] = 第一个像素的蓝色分量
     * // pixels[1] = 第一个像素的绿色分量
     * // pixels[2] = 第一个像素的红色分量
     * // pixels[3] = 第一个像素的透明度
     * </pre>
     *
     * @return 帧的像素数据，格式为BGRA，大小为width*height*4
     */
    public byte[] getPixels() {
        // 创建目标数组，大小为width*height*4
        // 4是每个像素的字节数（BGRA）
        byte[] pixels = new byte[width * height * 4];

        // 逐行读取Native内存中的数据
        for (int y = 0; y < height; y++) {
            // 计算当前行的偏移量：y * pitch
            // pitch考虑了内存对齐，可能大于width*4
            byte[] row = data.getByteArray((long) y * pitch, width * 4);

            // 将当前行数据复制到目标数组的正确位置
            // 目标位置：y * width * 4
            System.arraycopy(row, 0, pixels, y * width * 4, row.length);
        }

        return pixels;
    }
}