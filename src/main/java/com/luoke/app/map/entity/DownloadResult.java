package com.luoke.app.map.entity;

import lombok.Data;

/**
 * 瓦片下载结果封装类
 *
 * <p>封装瓦片下载操作的结果状态，包括成功、失败和未找到三种状态。</p>
 *
 * <p><b>设计模式：</b>使用静态工厂方法模式创建不同状态的结果对象，
 * 相比直接使用构造器更清晰、更安全。</p>
 *
 * <p><b>状态说明：</b></p>
 * <ul>
 *   <li>成功状态：success=true，包含下载的瓦片数据</li>
 *   <li>未找到状态：notFound=true，表示服务器返回404或类似错误</li>
 *   <li>失败状态：success=false且notFound=false，表示网络错误、超时等</li>
 * </ul>
 *
 * <p><b>内存管理注意事项：</b></p>
 * <ul>
 *   <li>data字段持有字节数组引用，仅在成功状态下有值</li>
 *   <li>失败和未找到状态下data为null，不占用额外内存</li>
 *   <li>建议在处理完结果后及时释放data引用，让GC回收内存</li>
 *   <li>byte数组是纯Java对象，无需特殊清理</li>
 * </ul>
 *
 * <p><b>Native Image兼容性：</b></p>
 * <ul>
 *   <li>不使用反射或动态代理，完全兼容GraalVM Native Image</li>
 *   <li>Lombok生成的代码在编译时已生成</li>
 *   <li>静态工厂方法无运行时动态特性</li>
 * </ul>
 *
 * @author RocoMapTracker Team
 * @version 1.0
 */
@Data
public class DownloadResult {
    /**
     * 下载的瓦片图像数据
     *
     * <p><b>数据内容：</b>仅在下载成功时包含实际的瓦片图像字节数组（通常是PNG/JPG格式）</p>
     * <p><b>状态关联：</b>只有当success=true时，此字段才有有效数据</p>
     * <p><b>默认值：</b>null（在失败和未找到状态下）</p>
     *
     * <p><b>内存生命周期：</b></p>
     * <ul>
     *   <li>由DownloadResult对象持有引用</li>
     *   <li>当DownloadResult对象不可达时，GC会自动回收byte数组</li>
     *   <li>在长时间运行的下载任务中，应及时置null释放内存</li>
     * </ul>
     */
    private byte[] data;

    /**
     * 下载是否成功的标志
     *
     * <p><b>true表示：</b>下载成功，data字段包含有效的瓦片数据</p>
     * <p><b>false表示：</b>下载失败，可能是网络错误、服务器错误等</p>
     * <p><b>默认值：</b>false</p>
     *
     * <p><b>使用建议：</b>检查此字段以判断是否可以使用data字段的数据</p>
     */
    private boolean success;

    /**
     * 资源未找到的标志
     *
     * <p><b>true表示：</b>服务器返回404或类似"资源不存在"的错误</p>
     * <p><b>false表示：</b>资源存在或发生了其他类型的错误</p>
     * <p><b>默认值：</b>false</p>
     *
     * <p><b>区别于success：</b></p>
     * <ul>
     *   <li>notFound=true：明确的"资源不存在"错误</li>
     *   <li>success=false且notFound=false：其他类型的错误（网络、超时等）</li>
     * </ul>
     */
    private boolean notFound;

    /**
     * 创建表示下载成功的DownloadResult对象
     *
     * <p><b>工厂方法优势：</b></p>
     * <ul>
     *   <li>封装对象创建逻辑，确保状态一致性</li>
     *   <li>避免直接操作字段，减少错误</li>
     *   <li>方法名清晰表达意图</li>
     * </ul>
     *
     * <p><b>内存管理：</b></p>
     * <ul>
     *   <li>此方法接收byte数组引用，不进行复制</li>
     *   <li>调用方需确保在调用后仍正确管理数组生命周期</li>
     *   <li>如果调用方不再需要原始数组，应传递后置null</li>
     * </ul>
     *
     * @param data 下载成功的瓦片图像数据，不能为null
     * @return DownloadResult对象，success=true，data包含传入的数据
     *
     * @see #notFound()
     * @see #failed()
     */
    public static DownloadResult success(byte[] data) {
        // 创建新的结果对象
        DownloadResult r = new DownloadResult();

        // 设置下载的数据和成功标志
        // 注意：这里直接引用传入的数组，不进行复制，性能更好
        r.data = data;
        r.success = true;

        return r;
    }

    /**
     * 创建表示资源未找到的DownloadResult对象
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>服务器返回HTTP 404状态码</li>
     *   <li>API明确表示资源不存在</li>
     *   <li>需要区分"资源不存在"和"下载失败"的情况</li>
     * </ul>
     *
     * <p><b>内存效率：</b></p>
     * <ul>
     *   <li>此状态不包含data字段，避免不必要的内存占用</li>
     *   <li>适合批量处理未找到的瓦片请求</li>
     * </ul>
     *
     * @return DownloadResult对象，notFound=true，success=false，data=null
     *
     * @see #success(byte[])
     * @see #failed()
     */
    public static DownloadResult notFound() {
        // 创建新的结果对象
        DownloadResult r = new DownloadResult();

        // 设置未找到标志
        // data保持null，节省内存
        r.notFound = true;

        return r;
    }

    /**
     * 创建表示下载失败的DownloadResult对象
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>网络连接超时</li>
     *   <li>服务器返回5xx错误</li>
     *   <li>网络中断</li>
     *   <li>其他非"未找到"类型的错误</li>
     * </ul>
     *
     * <p><b>与notFound()的区别：</b></p>
     * <ul>
     *   <li>failed()：可重试的错误（网络问题等）</li>
     *   <li>notFound()：明确的"不存在"错误，无需重试</li>
     * </ul>
     *
     * <p><b>内存效率：</b></p>
     * <ul>
     *   <li>此状态不包含data字段，避免不必要的内存占用</li>
     *   <li>返回一个默认构造的对象，所有字段保持默认值</li>
     * </ul>
     *
     * @return DownloadResult对象，success=false，notFound=false，data=null
     *
     * @see #success(byte[])
     * @see #notFound()
     */
    public static DownloadResult failed() {
        // 返回默认构造的对象
        // 默认值：success=false, notFound=false, data=null
        // 这正是我们需要的"失败"状态，无需额外设置
        return new DownloadResult();
    }
}