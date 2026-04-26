package com.luoke.app.capture.jna;

import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

/**
 *   * 帧回调接口
 * <p>
 * 该接口定义了Native层调用Java层的回调方法。
 * 用于将捕获的帧数据从Native层传递到Java层。
 *
 * <h3>接口设计</h3>
 * <ul>
 *   <li>继承StdCallCallback，使用stdcall调用约定</li>
 *   <li>由Native层调用，在Native线程中执行</li>
 *   <li>使用基本类型和Pointer参数</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>回调在Native线程中执行</li>
 *   <li>不是在主线程中执行</li>
 *   <li>需要注意线程安全问题</li>
 * </ul>
 *
 * <h3>内存管理</h3>
 * <ul>
 *   <li>data指针由Native层管理</li>
 *   <li>不要在Java层释放该指针</li>
 *   <li>帧数据在回调后可能被回收</li>
 * </ul>
 *
 * @author RocoMapTracker Team
 * @since 1 differentiated.0
 */
public interface FrameCallback extends StdCallLibrary.StdCallCallback {

    /**
     * 帧回调方法
     * <p>
     * 该方法由Native层调用，传递捕获的帧数据：
     * <ol>
     *   <li>Native层捕获新帧</li>
     *   <li>调用该方法传递帧数据</li>
     *   <li>Java层处理帧数据</li>
     *   <li>Native层释放帧资源</li>
     * </ol>
     *
     * <h3>参数说明</h3>
     * <ul>
     *   <li>data: Native内存指针，指向帧数据</li>
     *   <li>len: 数据长度（字节）</li>
     *   <li>w: 帧宽度（像素）</li>
     *   <li>h: 帧高度（像素）</li>
     *   <li>pitch: 每行字节数（考虑内存对齐）</li>
     *   <li>code: 错误码，0表示成功</li>
     * </ul>
     *
     * <h3>数据格式</h3>
     * <ul>
     *   <li>像素格式：BGRA</li>
     *   <li>每个像素4字节</li>
     *   <li>pitch考虑了GPU内存对齐</li>
     * </ul>
     *
     * <h3>线程安全</h3>
     * <ul>
     *   <li>该方法在Native线程中执行</li>
     *   <li>需要考虑线程安全问题</li>
     *   <li>避免直接修改共享状态</li>
     * </ul>
     *
     * <h3>性能考虑</h3>
     * <ul>
     *   <li>回调频率可能很高（30-60FPS）</li>
     *   <li>建议快速处理或异步处理</li>
     *   <li>避免阻塞回调线程</li>
     * </ul>
     *
     * @param data Native内存指针，指向帧数据
     * @param len 数据长度（字节）
     * @param w 帧宽度（像素）
     * @param h 帧高度（像素）
     * @param pitch 每行字节数（考虑内存对齐）
     * @param code 错误码，0表示成功，非0表示错误
     */
    void onFrame(Pointer data, long len, int w, int h, int pitch, int code);
}