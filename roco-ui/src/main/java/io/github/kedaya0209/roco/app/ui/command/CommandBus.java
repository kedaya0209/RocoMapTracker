package io.github.kedaya0209.roco.app.ui.command;

import io.github.kedaya0209.roco.app.hook.EventBus;
import net.jcip.annotations.ThreadSafe;

/**
 * 命令调度门面 — UI 组件的唯一状态写入入口。
 * <p>
 * 内部使用独立的 {@link EventBus} 实例（与 {@link io.github.kedaya0209.roco.app.hook.AppEvents} 隔离）。
 * Handler 通过 {@link #subscribe(Class, java.util.function.Consumer)} 注册，
 * UI 通过 {@link #dispatch(UiCommand)} 触发。
 */
@ThreadSafe
public final class CommandBus {

    private static final EventBus bus = new EventBus();

    private CommandBus() {
    }

    /** 注册命令处理器 */
    public static <T extends UiCommand> void subscribe(Class<T> type, java.util.function.Consumer<T> handler) {
        bus.subscribe(type, handler);
    }

    /** 分发命令（同步，调用线程上执行 handler） */
    @SuppressWarnings("unchecked")
    public static <T extends UiCommand> void dispatch(T command) {
        bus.publish((Class<T>) command.getClass(), command);
    }

    /** 关闭异步 worker（当前同步模式，保留以与 EventBus 接口一致） */
    public static void shutdown() {
        bus.shutdown();
    }
}
