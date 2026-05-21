package com.luoke.app.process;

import net.jcip.annotations.ThreadSafe;

/**
 * 子进程创建工厂 — 函数式接口，替代 {@link NativeProcess#create} 静态调用。
 * <p>
 * 默认实现：{@code NativeProcess::create}
 * 测试时可注入 mock 实现，无需真实启动子进程。
 */
@FunctionalInterface
@ThreadSafe
public interface NativeProcessFactory {

    /**
     * 创建子进程
     *
     * @param commandLine    完整命令行
     * @param hJob           JobObject 句柄（0 = 不加入 Job）
     * @param redirectStdout 是否重定向 stdout
     * @return NativeProcess 实例，失败返回 null
     */
    NativeProcess create(String commandLine, long hJob, boolean redirectStdout);
}
