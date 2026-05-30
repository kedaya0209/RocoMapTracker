package com.luoke.app.map.dto;

import lombok.Data;
import net.jcip.annotations.NotThreadSafe;


@Data
@NotThreadSafe
public class DownloadResult {

    private byte[] data;

    private boolean success;

    private boolean notFound;

    public static DownloadResult success(byte[] data) {
        // 创建新的结果对象
        DownloadResult r = new DownloadResult();

        // 设置下载的数据和成功标志
        // 注意：这里直接引用传入的数组，不进行复制，性能更好
        r.data = data;
        r.success = true;

        return r;
    }

    public static DownloadResult notFound() {
        // 创建新的结果对象
        DownloadResult r = new DownloadResult();

        // 设置未找到标志
        // data保持null，节省内存
        r.notFound = true;

        return r;
    }

    public static DownloadResult failed() {
        // 返回默认构造的对象
        // 默认值：success=false, notFound=false, data=null
        // 这正是我们需要的"失败"状态，无需额外设置
        return new DownloadResult();
    }
}