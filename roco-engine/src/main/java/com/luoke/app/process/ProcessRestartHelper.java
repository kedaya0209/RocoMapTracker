package com.luoke.app.process;

import net.jcip.annotations.ThreadSafe;
import com.luoke.app.socket.SocketServer;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 子进程崩溃重启辅助工具 — 提供异常安全的异步重启。
 *
 * <p>职责：
 * <ul>
 *   <li>延迟执行（等待旧进程完全退出）</li>
 *   <li>并发防护（CAS 令牌，防止多线程同时重启）</li>
 *   <li>Server-alive 检查（重启发动前确认 SocketServer 仍在运行）</li>
 *   <li>全局异常兜底，防止虚拟线程静默死亡</li>
 * </ul>
 *
 * <p>速率限制由各进程管理器自身负责（如 {@code SiftProcessManager.restartAfterCrash} 自带速率限制），
 * 本组件不叠加冗余的速率限制。</p>
 *
 * <p>所有子进程 Handler（{@link com.luoke.app.capture.CaptureHandler}、
 * {@link com.luoke.app.match.SiftMatchHandler}）共用此组件。</p>
 */
@ThreadSafe
@Slf4j
public class ProcessRestartHelper {

    private final String processName;
    private final long delayMs;
    /** CAS 令牌 — 防止并发重启，{@link #delayMs} 过后归还 */
    private final AtomicLong restartToken = new AtomicLong(0);

    /**
     * @param processName 进程名称（日志用）
     * @param delayMs     崩溃后等待多久再发起重启，等待旧进程完全退出
     */
    public ProcessRestartHelper(String processName, long delayMs) {
        this.processName = processName;
        this.delayMs = delayMs;
    }

    /**
     * 异步重启子进程。
     * <ol>
     *   <li>延迟 {@code delayMs} 后执行，等待旧进程完全退出</li>
     *   <li>CAS 令牌防止并发重启</li>
     *   <li>启动前确认 {@code server.isRunning()}</li>
     *   <li>所有异常被内部捕获并记录，不会静默死亡</li>
     * </ol>
     *
     * @param server  SocketServer 实例
     * @param launcher 实际执行进程启动的逻辑
     */
    public void restartAsync(SocketServer server, ProcessLauncher launcher) {
        Thread.ofVirtual().name(processName + "-restart").start(() -> {
            try {
                // 第一阶段：CAS 抢令牌（无令牌或上一个令牌已被消费时才放行）
                long token = restartToken.get();
                if (token != 0) {
                    log.warn("[{}] 上一次重启还未完成 (token={})，跳过本次重启请求", processName, token);
                    return;
                }
                long now = System.currentTimeMillis();
                if (!restartToken.compareAndSet(0, now)) {
                    return; // 并发竞争，另一个线程已在重启
                }

                // 第二阶段：延迟等待
                Thread.sleep(delayMs);

                // 第三阶段：前置检查
                if (!server.isRunning()) {
                    log.warn("[{}] SocketServer 未运行，跳过重启", processName);
                    restartToken.set(0);
                    return;
                }

                // 第四阶段：执行重启
                boolean ok = launcher.launch(server);
                if (ok) {
                    log.info("[{}] 重启成功", processName);
                } else {
                    log.warn("[{}] 重启失败（进程管理器拒绝）", processName);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // 重启回调可能抛出多种异常，保留通用捕获
                log.error("[{}] 重启异常", processName, e);
            } finally {
                // 归还令牌，允许下一次重启
                restartToken.set(0);
            }
        });
    }

    /** 重置令牌（stop/start 时调用，清除上一次可能残留的锁定） */
    public void reset() {
        restartToken.set(0);
    }

    @FunctionalInterface
    public interface ProcessLauncher {
        boolean launch(SocketServer server);
    }
}
