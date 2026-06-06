package io.github.kedaya0209.roco.app.ui.command;

import net.jcip.annotations.ThreadSafe;

/**
 * 命令标记接口 — 所有 UI Command record 实现此接口。
 * 通过 {@link CommandBus#dispatch(UiCommand)} 执行。
 */
@ThreadSafe
public interface UiCommand {
}
