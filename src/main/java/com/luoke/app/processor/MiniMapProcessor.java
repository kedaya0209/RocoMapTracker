package com.luoke.app.processor;

import com.luoke.app.capture.common.CaptureFrameRecord;

/**
 * 小地图处理器工具类
 * <p>
 * 该类提供从原始截屏帧中提取小地图区域的静态方法，主要功能包括：
 * <ul>
 *   <li>根据比例坐标提取正方形小地图区域</li>
 *   <li>应用圆形遮罩使小地图呈现圆形显示效果</li>
 *   <li>处理BGRA格式的图像数据（4字节像素）</li>
 * </ul>
 * <p>
 * <b>图像格式说明：</b>
 * <ul>
 *   <li>输入/输出格式：BGRA（每像素4字节）</li>
 *   <li>字节顺序：B(蓝)、G(绿)、R(红)、A(透明度)</li>
 *   <li>内存布局：行优先（row-major）</li>
 * </ul>
 * <p>
 * <b>内存安全：</b>
 * <ul>
 *   <li>所有方法都对坐标和尺寸进行边界检查</li>
 *   <li>进行数组长度验证防止数组越界</li>
 *   <li>不持有任何Native资源引用，由调用方管理</li>
 * </ul>
>
 * <b>使用场景：</b>
 * 主要用于游戏识别场景，从完整游戏画面中提取小地图区域并应用圆形遮罩，
 * 以便进行后续的地图匹配和定位操作。
 *
 * @since 1.0
 */
public final class MiniMapProcessor {

    /**
     * 私有构造方法，防止实例化
     * <p>
     * 该类为纯工具类，所有方法均为静态方法，无需实例化。
     * 私有构造方法可以防止用户误用，确保类的设计意图被正确遵循。
     */
    private MiniMapProcessor() {
    }

    /**
     * 从完整截屏帧中提取正方形小地图并应用圆形遮罩
     * <p>
     * 该方法根据给定的比例坐标和高度比例，从原始截屏帧中提取正方形小地图区域，
     * 并自动应用圆形遮罩。提取的小地图尺寸为正方形，边长由高度比例决定但受限于
     * 可用空间。
     * <p>
     * <b>坐标计算逻辑：</b>
     * <ul>
     *   <li>起始X坐标 = fullWidth * xRatio</li>
     *   <li>起始Y坐标 = fullHeight * yRatio</li>
     *   <li>目标高度 = fullHeight * hRatio</li>
     *   <li>实际边长 = min(目标高度, 可用宽度, 可用高度)</li>
     * </ul>
     * <p>
     * <b>边界保护：</b>
     * <ul>
     *   <li>如果计算出的边长 <= 0，返回null</li>
     *   <li>自动限制在图像边界内</li>
     *   <li>确保提取区域不会超出图像范围</li>
     * </ul>
     *
     * @param frameRecord 原始截屏帧记录对象，包含BGRA格式的图像数据
     * @param xRatio 小地图左上角X坐标相对于完整宽度的比例（0.0-1.0）
     * @param yRatio 小地图左上角Y坐标相对于完整高度的比例（0.0-1.0）
     * @param hRatio 小地图高度相对于完整高度的比例（0.0-1.0）
     * @return 处理后的截屏帧记录，包含应用圆形遮罩后的正方形小地图数据；
     *         如果输入参数无效或提取区域无效，返回null
     */
    public static CaptureFrameRecord extractFinalMiniMap(
            CaptureFrameRecord frameRecord,
            double xRatio,
            double yRatio,
            double hRatio
    ) {
        // 参数有效性检查：frameRecord为null或数据为空时无法处理
        if (frameRecord == null || frameRecord.bytes() == null) return null;

        // 获取原始图像的完整尺寸
        int fullWidth = frameRecord.width();
        int fullHeight = frameRecord.height();

        // 计算小地图的目标尺寸和起始坐标
        int h = (int) (fullHeight * hRatio);  // 根据比例计算目标高度
        int startX = (int) (fullWidth * xRatio);  // 根据比例计算起始X坐标
        int startY = (int) (fullHeight * yRatio);  // 根据比例计算起始Y坐标

        // 计算从起始点到边界可用的最大空间
        int maxPossibleW = fullWidth - startX;  // X方向剩余可用宽度
        int maxPossibleH = fullHeight - startY;  // Y方向剩余可用高度

        // 自动调整为正方形：取目标高度和可用空间的最小值
        // 这样可以确保提取区域始终为正方形且在图像边界内
        int squareSize = Math.min(h, Math.min(maxPossibleW, maxPossibleH));

        // 如果计算出的正方形尺寸无效，返回null
        if (squareSize <= 0) return null;

        // 提取矩形区域并应用圆形遮罩
        // squareSize作为宽度和高度确保正方形
        return extractCircleMaskMiniMapBytes(frameRecord.bytes(), fullWidth, fullHeight,
                                             startX, startY, squareSize, squareSize);
    }

    /**
     * 从原始图像数据中提取指定区域并应用圆形遮罩
     * <p>
     * 该方法首先提取指定矩形区域的图像数据，然后应用圆形遮罩使该区域呈现圆形显示效果。
     * 提取的区域可以是任意矩形，不限于正方形。
     * <p>
     * <b>处理流程：</b>
     * <ol>
     *   <li>调用extractMiniMapBytes提取指定矩形区域</li>
     *   <li>如果提取成功，调用applyCircleMask应用圆形遮罩</li>
     *   <li>封装结果到CaptureFrameRecord对象返回</li>
     * </ol>
     * <p>
     * <b>内存管理：</b>
     * <ul>
     *   <li>中间字节数组由内部方法创建和管理</li>
     *   <li>返回的CaptureFrameRecord持有最终结果数据的引用</li>
     *   <li>调用方负责释放返回对象及其持有的数据</li>
     * </ul>
     *
     * @param bytes 原始BGRA格式图像数据数组
     * @param fullWidth 原始图像的完整宽度（像素）
     * @param fullHeight 原始图像的完整高度（像素）
     * @param x 提取区域的左上角X坐标（像素）
     * @param y 提取区域的左上角Y坐标（像素）
     * @param width 提取区域的宽度（像素）
     * @param height 提取区域的高度（像素）
     * @return 包含应用圆形遮罩后图像数据的CaptureFrameRecord对象；
     *         如果提取失败，返回null
     */
    public static CaptureFrameRecord extractCircleMaskMiniMapBytes(
            byte[] bytes,
            int fullWidth,
            int fullHeight,
            int x,
            int y,
            int width,
            int height) {

        // 步骤1：从原始图像中提取指定矩形区域
        bytes = extractMiniMapBytes(fullWidth, fullHeight, bytes, x, y, width, height);

        // 步骤2：应用圆形遮罩（仅当提取成功时）
        if (bytes != null) {
            // 应用圆形遮罩，圆外区域透明度设为0
            bytes = applyCircleMask(bytes, width, height);

            // 封装结果到CaptureFrameRecord对象
            // 使用builder模式创建不可变对象
            return CaptureFrameRecord.builder()
                    .width(width)     // 设置宽度
                    .height(height)   // 设置高度
                    .bytes(bytes)     // 设置图像数据
                    .build();
        }

        // 提取失败返回null
        return null;
    }

    /**
     * 从原始图像数据中提取指定矩形区域
     * <p>
     * 该方法实现图像裁剪功能，从原始BGRA格式图像数据中提取指定矩形区域。
     * 执行边界检查确保提取区域不会越界，并验证输入数据的完整性。
     * * <b>算法说明：</b>
     * <ul>
     *   <li>使用System.arraycopy进行高效的内存拷贝</li>
     *   <li>按行拷贝数据，保持行优先布局</li>
     *   <li>每行拷贝连续的像素数据</li>
     * </ul>
     * <p>
     * <b>性能考虑：</b>
     * <ul>
     *   <li>使用System.arraycopy比手动循环拷贝更高效</li>
     *   <li>一次性分配目标数组，避免多次扩容</li>
     *   <li>不使用缓冲区，直接拷贝到目标数组</li>
     * </ul>
     * <p>
     * <b>内存安全：</b>
     * <ul>
     *   <li>验证输入数组长度是否足够</li>
     *   <li>限制坐标在有效范围内</li>
     *   <li>防止整数溢出（使用long进行长度检查）</li>
     * </ul>
     *
     * @param fullWidth 原始图像的完整宽度（像素）
     * @param fullHeight 原始图像的完整高度（像素）
     * @param src 原始BGRA格式图像数据数组
     * @param x 提取区域的左上角X坐标（像素）
     * @param y 提取区域的左上角Y坐标（像素）
     * @param w 提取区域的期望宽度（像素）
     * @param h 提取区域的期望高度（像素）
     * @return 提取出的图像数据数组（BGRA格式）；
     *         如果输入无效或数据不完整，返回null
     */
    public static byte[] extractMiniMapBytes(
            int fullWidth,
            int fullHeight,
            byte[] src,
            int x,
            int y,
            int w,
            int h) {

        // 基本参数有效性检查
        if (src == null || fullWidth <= 0 || fullHeight <= 0) return null;

        // 边界保护：确保提取坐标在有效范围内
        // 使用Math.min和Math.max限制坐标不会超出图像边界
        int safeX = Math.max(0, Math.min(x, fullWidth - 1));
        int safeY = Math.max(0, Math.min(y, fullHeight - 1));

        // 边界保护：确保提取尺寸不会超出剩余空间
        int safeW = Math.min(w, fullWidth - safeX);  // 限制宽度不超过可用宽度
        int safeH = Math.min(h, fullHeight - safeY);  // 限制高度不超过可用高度

        // 内存安全检查：验证源数组长度是否足够容纳完整的BGRA图像
        // 使用long避免整数溢出，每个像素4字节（BGRA）
        if (src.length < (long) fullWidth * fullHeight * 4) return null;

        // 分配目标数组，大小为提取区域的像素数 × 4（BGRA）
        byte[] miniBytes = new byte[safeW * safeH * 4];

        // 按行拷贝图像数据
        for (int row = 0; row < safeH; row++) {
            // 计算源数组中当前行的起始位置
            // 公式：(Y坐标 + 当前行号) × 完整宽度 + X坐标，再 × 4（每像素4字节）
            int srcPos = ((safeY + row) * fullWidth + safeX) * 4;

            // 计算目标数组中当前行的起始位置
            // 公式：当前行号 × 提取宽度，再 × 4（每像素4字节）
            int destPos = row * safeW * 4;

            // 使用System.arraycopy高效拷贝当前行的所有像素数据
            // 每行拷贝 safeW * 4 个字节
            System.arraycopy(src, srcPos, miniBytes, destPos, safeW * 4);
        }

        // 返回提取出的图像数据
        return miniBytes;
    }

    /**
     * 对BGRA格式图像应用圆形遮罩
     * <p>
     * 该方法将矩形图像转换为圆形显示效果，圆形区域外的像素透明度设为0（完全透明），
     * 圆形区域内的像素保持原有的颜色和透明度。
     * <p>
     * <b>圆形计算逻辑：</b>
     * <ul>
     *   <li>圆心坐标：(width-1)/2, (height-1)/2</li>
     *   <li>半径：min(width, height)/2</li>
     *   <li>判断条件：(x-cx)² + (y-cy)² <= radius²</li>
     * </ul>
     * <p>
     * <b>性能优化：</b>
     * <ul>
     *   <li>预先计算半径的平方，避免重复计算</li>
     *   <li>在外层循环预先计算dy和dy²，减少内层重复计算</li>
     *   <li>使用距离平方比较，避免开方运算</li>
     * </ul>
     * <p>
     * <b>内存特性：</b>
     * <ul>
     *   <li>创建新的目标数组，不修改原始数据</li>
     *   <li>目标数组大小与源数组相同</li>
     *   <li>每个像素独立处理，无内存依赖</li>
     * </ul>
     *
     * @param srcBytes 源BGRA格式图像数据数组
     * @param width 图像宽度（像素）
     * @param height 图像高度（像素）
     * @return 应用圆形遮罩后的图像数据数组；
     *         如果输入无效，返回null
     */
    public static byte[] applyCircleMask(byte[] srcBytes, int width, int height) {
        // 参数有效性检查：确保输入数组大小正确
        if (srcBytes == null || srcBytes.length != width * height * 4) return null;

        // 分配目标数组，大小与源数组相同
        byte[] dstBytes = new byte[width * height * 4];

        // 计算圆心坐标（使用减1确保奇数尺寸时圆心在中心像素）
        double cx = (width - 1) / 2.0;  // 圆心X坐标
        double cy = (height - 1) / 2.0; // 圆心Y坐标

        // 计算半径和半径的平方
        // 使用min确保圆形不会超出矩形边界
        double radius = Math.min(width, height) / 2.0;
        double radiusSq = radius * radius;  // 预先计算半径的平方，避免重复乘法

        // 遍历每个像素
        for (int y = 0; y < height; y++) {
            // 计算当前行到圆心的距离的平方
            // 这个值在内层循环中是不变的，可以预先计算
            double dy = y - cy;        // Y方向距离
            double dySq = dy * dy;     // Y方向距离的平方

            for (int x = 0; x < width; x++) {
                // 计算当前像素在数组中的索引
                // 公式：(行号 × 宽度 + 列号) × 4（每像素4字节）
                int idx = (y * width + x) * 4;

                // 复制像素的RGB颜色值（保留原色）
                dstBytes[idx] = srcBytes[idx];         // B（蓝色通道）
                dstBytes[idx + 1] = srcBytes[idx + 1]; // G（绿色通道）
                dstBytes[idx + 2] = srcBytes[idx + 2]; // R（红色通道）

                // 计算当前像素到圆心的距离的平方
                double dx = x - cx;            // X方向距离
                double distSq = dx * dx + dySq; // 欧几里得距离的平方

                // 根据距离判断是否在圆内
                if (distSq <= radiusSq) {
                    // 圆内：保持原透明度
                    dstBytes[idx + 3] = srcBytes[idx + 3]; // A（alpha通道）
                } else {
                    // 圆外：设为完全透明（alpha = 0）
                    dstBytes[idx + 3] = 0;
                }
            }
        }

        // 返回应用遮罩后的图像数据
        return dstBytes;
    }
}
