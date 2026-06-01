package io.github.kedaya0209.roco.app.socket;

import net.jcip.annotations.ThreadSafe;

/**
 * 系统事件 serviceId 常量 — 保留负数段，和业务消息走相同的路由机制
 * 任何客户端都可以订阅这些事件用于监控
 */
@ThreadSafe
public final class SystemEvents {

    private SystemEvents() {}

    /** 新客户端完成注册 */
    public static final int CLIENT_CONNECTED = -1;

    /** 客户端断开连接 */
    public static final int CLIENT_DISCONNECTED = -2;

    /** 新服务被注册 */
    public static final int SERVICE_REGISTERED = -3;

    /** 服务被取消注册 */
    public static final int SERVICE_UNREGISTERED = -4;

    /** 断开原因：正常断开（客户端主动关闭） */
    public static final int REASON_NORMAL = 0;

    /** 断开原因：IO 异常（连接意外中断） */
    public static final int REASON_IO_ERROR = 1;

    /** 断开原因：HELLO 超时或格式错误 */
    public static final int REASON_HELLO_ERROR = 2;

    /** 断开原因：服务器关闭 */
    public static final int REASON_SERVER_STOP = 3;

    /**
     * 序列化 CLIENT_CONNECTED 事件 body
     */
    public static byte[] encodeConnected(String clientId, java.util.Set<Integer> provides, java.util.Set<Integer> subscribes) {
        byte[] nameBytes = clientId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int size = 2 + nameBytes.length + 2 + provides.size() * 4 + 2 + subscribes.size() * 4;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.BIG_ENDIAN);
        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);
        buf.putShort((short) provides.size());
        for (int id : provides) buf.putInt(id);
        buf.putShort((short) subscribes.size());
        for (int id : subscribes) buf.putInt(id);
        return buf.array();
    }

    /**
     * 序列化 CLIENT_DISCONNECTED 事件 body
     */
    public static byte[] encodeDisconnected(String clientId, int reason) {
        byte[] nameBytes = clientId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(2 + nameBytes.length + 4).order(java.nio.ByteOrder.BIG_ENDIAN);
        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);
        buf.putInt(reason);
        return buf.array();
    }
}
