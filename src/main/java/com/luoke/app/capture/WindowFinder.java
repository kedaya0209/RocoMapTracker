package com.luoke.app.capture;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

/**
 * Windows窗口查找工具
 * <p>
 * 该类提供精确的Windows窗口查找功能，基于窗口标题匹配目标窗口。
 * 核心功能包括：
 * <ul>
 *   <li>枚举所有可见窗口</li>
 *   <li>清理窗口标题中的不可见字符</li>
 *   <li>精确匹配窗口标题</li>
 *   <li>返回窗口句柄供后续操作</li>
 * </ul>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>精确匹配：使用equals而非contains，避免浏览器标签干扰</li>
 *   <li>字符清理：移除所有控制字符和不可见字符</li>
 *   <li>性能优化：找到目标后立即停止枚举</li>
 * </ul>
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>根据窗口标题查找游戏窗口</li>
 *   <li>支持Native Image环境运行</li>
 *   <li>处理包含特殊字符的窗口标题</li>
 * </ul>
 *
 * @author RocoMapTracker Team
 * @since 1.0
 */
public class WindowFinder {

    /**
     * 根据关键字查找窗口句柄
     * <p>
     * 该方法执行以下步骤查找窗口：
     * <ol>
     *   <li>清理输入的关键字，移除不可见字符</li>
     *   <li>枚举所有可见窗口</li>
     *   <li>对每个窗口获取标题并清理</li>
     *   <li>精确匹配窗口标题</li>
     *   <li>找到后立即返回窗口句柄</li>
     * </ol>
     *
     * <h3>匹配策略</h3>
     * <ul>
     *   <li>使用精确匹配（equals）而非包含匹配</li>
     *   <li>清理所有控制字符和不可见字符</li>
     *   <li>只考虑可见窗口</li>
     * </ul>
     *
     * <h3>性能优化</h3>
     * <ul>
     *   <li>找到目标后立即停止枚举</li>
     *   <li>使用单元素数组存储结果，避免集合开销</li>
     *   <li>512字符缓冲区足够大多数窗口标题</li>
     * </ul>
     *
     * <h3>线程安全</h3>
     * <ul>
     *   <li>该方法是线程安全的</li>
     *   <li>每次调用独立枚举窗口</li>
     *   <li>不依赖任何共享状态</li>
     * </ul>
     *
     * @param keyword 窗口标题关键字，将清理不可见字符后进行精确匹配
     * @return 窗口句柄，如果未找到则返回0
     */
    public static long findWindowByKeyword(String keyword) {
        // 1. 预清理传入的关键词，确保匹配的一致性
        final String target = cleanString(keyword);

        // 使用单元素数组存储找到的窗口句柄
        // 避免使用集合的开销，同时能在回调中修改
        WinDef.HWND[] foundHwnd = new WinDef.HWND[1];

        // 2. 枚举所有顶级窗口
        User32.INSTANCE.EnumWindows((hwnd, pointer) -> {
            // 3. 基础过滤：只处理可见窗口
            // 隐藏窗口通常不是我们要找的目标
            if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
                return true; // 继续枚举
            }

            // 4. 获取窗口标题
            char[] windowText = new char[512]; // 512字符缓冲区
            User32.INSTANCE.GetWindowText(hwnd, windowText, 512);
            String rawTitle = Native.toString(windowText);

            // 5. 深度清理：移除控制字符、零宽字符等
            String cleanTitle = cleanString(rawTitle);

            // 6. 精确匹配窗口标题
            // 使用equals实现最严谨的匹配，防止浏览器标签等干扰
            if (cleanTitle.equals(target)) {
                foundHwnd[0] = hwnd; // 保存找到的窗口句柄
                return false; // 找到后立即停止枚举
            }

            return true; // 继续枚举
        }, null);

        // 7. 返回结果
        // 如果未找到，foundHwnd[0]为null，返回0
        return foundHwnd[0] == null ? 0 : Pointer.nativeValue(foundHwnd[0].getPointer());
    }

    /**
     * 清理字符串：移除所有控制字符、不可见字符及首尾空格
     * <p>
     * 该方法执行以下清理步骤：
     * <ol>
     *   <li>移除Unicode控制字符（U+0000–U+001F, U+007F–U+009F）</li>
     *   <li>移除零宽字符（如零宽空格U+200B）</li>
     *   <li>将不间断空格（U+00A0）转换为标准空格</li>
     *   <li>移除首尾空白字符</li>
     * </ol>
     *
     * <h3>处理的字符类型</h3>
     * <ul类="table">
     *   <li>控制字符（Cntrl）: 换行符、制表符等</li>
     *   <li>格式字符（Cf）: 零宽字符、 bidi控制字符等</li>
     *   <li>段落/行分隔符（Zp/Zl）: 段落/行分隔符</li>
     *   <li>不间断空格（U+00A0）: 转换为标准空格</li>
     * </ul>
     *
     * <h3>使用场景</h3>
     * <ul>
     *   <li>清理窗口标题中的不可见字符</li>
     *   <li>处理从Native层获取的字符串</li>
     *   <li>确保字符串匹配的一致性</li>
     * </ul>
     *
     * <h3>性能考虑</h3>
     * <ul>
     *   <li>使用正则表达式，但调用次数有限</li>
     *   <li>链式操作减少中间对象创建</li>
     * </ul>
     *
     * @param input 要清理的字符串，可以为null
     * @return 清理后的字符串，如果输入为null则返回空字符串
     */
    private static String cleanString(String input) {
        // 处理null输入
        if (input == null) return "";

        // 1. 移除 Unicode 控制字符 (U+0000–U+001F, U+007F–U+009F)
        // 包括：换行符(\n)、制表符(\t)、回车符(\r)等
        return input
                .replaceAll("\\p{Cntrl}", "")
                // 2. 移除零宽空格等不可见字符
                // Cf: 格式字符，如零宽不连字(U+200C)、零宽连字(U+200D)
                // Zp: 段落分隔符(U+2029)
                // Zl: 行分隔符(U+2028)
                .replaceAll("[\\p{Cf}\\p{Zp}\\p{Zl}]", "")
                // 3. 将所有类型的空白（包括 NBSP）转为标准空格并修剪首尾
                // U+00A0: 不间断空格（Non-Breaking Space）
                .replace('\u00A0', ' ')
                .trim(); // 移除首尾空白字符
    }
}