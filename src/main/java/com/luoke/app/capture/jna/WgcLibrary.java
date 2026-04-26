package com.luoke.app.capture.jna;

import com.sun.jna.win32.StdCallLibrary;

/**
 * Windows Graphics Capture Native库接口
 * <p>
 * 该接口定义了与Native capture.dll交互的方法。
 * 使用JNA（Java Native Access）实现Java与Native代码的互操作。
 *
 * <h3>接口设计</h3>
 * <ul>
 *   <li>继承StdCallLibrary，使用stdcall调用约定</li>
 *   <li>方法名与Native C++函数名一致</li>
 *   <li>使用基本类型和接口参数</li>
 * </ul>
 *
 * <h3>Native资源管理</h3>
 * <ul>
 *   <li>init_capturer分配Native资源</li>
 *   <li>destroy_capturer释放Native资源</li>
 *   <li>必须成对调用，否则导致内存泄漏</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>init_capturer创建Native线程</li>
 *   <li>回调在Native线程中执行</li>
 *   <li>destroy_capturer停止Native线程</li>
 * </ul>
 *
 * @author RocoMapTracker Team
 * @since 1.0
 */
public interface WgcLibrary extends StdCallLibrary {

    /**
     * 初始化窗口捕获器
     * <p>
     * 该方法在Native层创建窗口捕获器并启动捕获循环：
     * <ol>
     *   <li>创建Direct3D设备</li>
     *   <li>创建帧池（ItemPool）</li>
     *   <li>创建帧会话（FrameArrival）</li>
     *   <li>启动捕获线程</li>
     * </ol>
     *
     * <h3>参数说明</h3>
     * <ul>
     *   <li>hwnd: 目标窗口句柄，必须为有效窗口</li>
     *   <li>showBorder: 是否显示捕获边框，1显示，0不显示</li>
     *   <li>callback: 帧回调接口，在Native线程中调用</li>
     * </ul>
     *
     * <h3>返回值</h3>
     * <ul>
     *   <li>0: 成功</li>
     *   <li>非0: 失败，错误码</li>
     * </ul>
     *
     * <h3>Native资源分配</h3>
     * <ul>
     *   <li>分配Direct3D设备</li>
     *   <li>分配帧池资源</li>
     *   <li>分配帧会话资源</li>
     *   <li>启动捕获线程</li>
     * </ul>
     *
     * <h3>错误处理</h3>
     * <ul>
     *   <li>窗口句柄无效：返回错误码</li>
     *   <li>D3D设备创建失败：返回错误码</li>
     *   <li>帧池创建失败：返回错误码</li>
     *   <li>其他异常：返回错误码</li>
     * </ul>
     *
     * @param hwnd 目标窗口句柄，指定要捕获的窗口
     * @param showBorder 是否显示捕获边框，1显示，0不显示
     * @param callback 帧回调接口，用于接收捕获的帧数据
     * @return 0表示成功，非0表示失败（错误码）
     * @see FrameCallback
     */
    int init_capturer(long hwnd, int showBorder, FrameCallback callback);

    /**
     * 销毁窗口捕获器
     * * <p>
     * 该方法释放由init_capturer分配的所有Native资源：
     * <ol>
     *   <li>停止捕获线程</li>
     *   <li>释放帧会话资源</li>
     *   <li>释放帧池资源</li>
     *   <li>释放Direct3D设备</li>
     * </ol>
     *
     * <h3>资源释放</h3>
     * <ul>
     *   <li>停止捕获线程，避免内存泄漏</li>
     *   <li>释放所有分配的Native资源</li>
     *   <li>清理回调接口引用</li>
     * </ul>
     *
     * <h3>线程安全</h3>
     * <ul>
     *   <li>可以安全地从任何线程调用</li>
     *   <li>会等待捕获线程安全退出</li>
     *   <li>不会导致死锁或竞态条件</li>
     * </ul>
     *
     * <h3>使用建议</h3>
     * <ul>
     *   <li>必须在不再需要捕获时调用</li>
     *   <li>建议在finally块中调用</li>
     *   <li>与init_capturer成对调用</li>
     * </ul>
     */
    void destroy_capturer();
}