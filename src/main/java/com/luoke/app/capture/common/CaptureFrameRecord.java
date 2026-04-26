package com.luoke.app.capture.common;

import lombok.Builder;

/**
 * 捕获帧记录类
 * <p>
 * 该类使用record定义，封装了捕获的帧数据信息。
 * 核心功能包括：
 * <ul>
 *   <li>存储帧的宽度信息</li>
 *   <li>存储帧的高度信息</li>
 *   <li>存储帧的像素数据</li>
 *   <li>使用Builder模式支持灵活构建</li>
 * </ul>
 *
 * <h3>数据格式</h3>
 * <ul>
 *   <li>像素格式：BGRA（蓝、绿、红、透明度）</li>
 *   <li>每个像素4字节</li>
 *   <li>数组长度应为width*height*4</li>
 * </ul>
 *
 * <h3>内存管理</h3>
 * <ul>
 *   <li>bytes数组持有帧数据的完整拷贝</li>
 *   <li>调用者需要管理bytes数组的生命周期</li>
 *   <li>建议在不再需要时释放资源</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>record类是不可变的，线程安全</li>
 *   <li>bytes数组不应被修改</li>
 *   <li>可以安全地在多线程间共享</li>
 * </ul>
 *
 * <h3>性能优化</h3>
 * <ul>
 *   <li>record类减少了样板代码</li>
 *   <li>Builder模式支持流畅的API</li>
 *   <li>避免了getter/setter的冗余代码</li>
 * </ul>
 *
 * @param width 帧宽度（像素）
 * @param height 帧高度（像素）
 * @param bytes 帧的像素数据，格式为BGRA，大小为width*height*4
 * @author RocoMapTracker Team
 * @since 1.0
 */
@Builder
public record CaptureFrameRecord(int width, int height, byte[] bytes) {
}
