package com.luoke.app.macher;

import com.luoke.app.socket.SocketSession;
import lombok.extern.slf4j.Slf4j;

/**
 * SIFT Socket 会话管理器 — 单一职责：管理 sift_match.exe 的 Socket 会话生命周期。
 *
 * <p>不持有任何 NativeProcess 引用，与进程管理完全解耦。
 * 状态变更通过返回值通知调用方（{@link SiftMatchHandler} 协调器），
 * 由协调器负责发布 Hook 事件和调用 StateCallback。</p>
 */
@Slf4j
public class SiftSessionManager {

    private volatile SocketSession activeSession;
    private volatile boolean activeInitialized;
    private volatile SocketSession pendingSession;
    private volatile boolean pendingInitialized;
    private volatile boolean switching;

    // ==================== 连接管理 ====================

    /**
     * 新会话绑定。
     * switching=true 时优先绑定到 pendingSession，否则绑定到 activeSession。
     */
    public void onConnect(SocketSession session) {
        if (switching && pendingSession == null) {
            this.pendingSession = session;
            log.info("SiftMatchHandler bound pending session #{}", session.id());
        } else if (activeSession == null || activeSession.isClosed()) {
            this.activeSession = session;
            log.info("SiftMatchHandler bound active session #{}", session.id());
        }
    }

    /**
     * 判断 session 是否属于 pending（热切换过程中）
     */
    public boolean isFromPending(SocketSession session) {
        return switching && session == pendingSession;
    }

    // ==================== 初始化完成/失败 ====================

    /**
     * Active 进程初始化完成
     *
     * @return 特征点数
     */
    public int handleInitComplete(byte[] body) {
        int featureCount = SiftMatchProtocol.decodeInitComplete(body);
        this.activeInitialized = true;
        log.info("SIFT ready, {} features", featureCount);
        return featureCount;
    }

    /**
     * Active 进程初始化失败
     *
     * @return 错误消息
     */
    public String handleInitFailed(byte[] body) {
        String msg = SiftMatchProtocol.decodeInitFailed(body);
        log.error("SIFT init failed: {}", msg);
        return msg;
    }

    // ==================== Pending 热切换 ====================

    /**
     * Pending 进程初始化完成 — 执行原子交换。
     *
     * @return 交换结果（旧 activeSession + 特征点数）
     */
    public SwapResult handlePendingInitComplete(byte[] body) {
        int featureCount = SiftMatchProtocol.decodeInitComplete(body);
        SocketSession oldActive = this.activeSession;

        this.activeSession = this.pendingSession;
        this.activeInitialized = true;
        this.pendingSession = null;
        this.pendingInitialized = false;
        this.switching = false;

        log.info("Seamless switch complete, {} features", featureCount);
        return new SwapResult(oldActive, featureCount);
    }

    /**
     * Pending 进程初始化失败
     *
     * @return 错误消息
     */
    public String handlePendingInitFailed(byte[] body) {
        String msg = SiftMatchProtocol.decodeInitFailed(body);
        log.error("Pending SIFT init failed: {}, keeping current active", msg);
        cancelPendingCleanup();
        return msg;
    }

    // ==================== 断开处理 ====================

    /**
     * Active 会话断开 — 清理 active 状态
     */
    public void handleActiveDisconnect() {
        log.warn("SiftMatchHandler active session disconnected");
        this.activeSession = null;
        this.activeInitialized = false;
    }

    /**
     * 取消 pending 切换，返回待关闭的旧 pendingSession
     */
    public SocketSession cancelPendingCleanup() {
        SocketSession s = pendingSession;
        pendingSession = null;
        pendingInitialized = false;
        switching = false;
        return s;
    }

    // ==================== 查询 ====================

    public boolean isReady() {
        return activeInitialized && activeSession != null && !activeSession.isClosed();
    }

    public boolean isSwitching() {
        return switching;
    }

    public void enterSwitching() {
        this.switching = true;
    }

    /**
     * 重置 switching 标志（启动 pending 失败时使用）
     */
    public void resetSwitching() {
        this.switching = false;
    }

    /**
     * 完全重置所有状态（stop 时使用）
     */
    public void reset() {
        activeSession = null;
        activeInitialized = false;
        pendingSession = null;
        pendingInitialized = false;
        switching = false;
    }

    public SocketSession getActiveSession() {
        return activeSession;
    }

    public SocketSession getPendingSession() {
        return pendingSession;
    }

    // ==================== 内嵌值对象 ====================

    /**
     * 热切换交换结果
     *
     * @param oldActiveSession 旧的 active 会话（调用方负责关闭）
     * @param featureCount     SIFT 特征点数
     */
    public record SwapResult(SocketSession oldActiveSession, int featureCount) {
    }
}
