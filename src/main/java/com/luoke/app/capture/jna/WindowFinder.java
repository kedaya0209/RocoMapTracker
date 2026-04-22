package com.luoke.app.capture.jna;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

/**
 * 工业级窗口查找工具
 */
public class WindowFinder {

    /**
     * 精确匹配窗口名，排除不可见字符干扰
     */
    public static long findWindowByKeyword(String keyword) {
        // 预清理传入的关键词
        final String target = cleanString(keyword);
        WinDef.HWND[] foundHwnd = new WinDef.HWND[1];

        User32.INSTANCE.EnumWindows((hwnd, pointer) -> {
            // 1. 基础过滤：只看可见窗口
            if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
                return true;
            }

            // 2. 获取标题
            char[] windowText = new char[512];
            User32.INSTANCE.GetWindowText(hwnd, windowText, 512);
            String rawTitle = Native.toString(windowText);

            // 3. 深度清理：移除控制字符、零宽字符等
            String cleanTitle = cleanString(rawTitle);

            // 4. 语义匹配
            // 使用 equals 实现最严谨的匹配，防止浏览器标签干扰
            if (cleanTitle.equals(target)) {
                foundHwnd[0] = hwnd;
                return false; // 找到后立即停止枚举
            }

            return true;
        }, null);

        return foundHwnd[0] == null ? 0 : Pointer.nativeValue(foundHwnd[0].getPointer());
    }

    /**
     * 清理字符串：移除所有控制字符、不可见字符及首尾空格
     */
    private static String cleanString(String input) {
        if (input == null) return "";
        return input
                // 移除 Unicode 控制字符 (U+0000–U+001F, U+007F–U+009F)
                .replaceAll("\\p{Cntrl}", "")
                // 移除零宽空格等不可见字符 (如 U+200B)
                .replaceAll("[\\p{Cf}\\p{Zp}\\p{Zl}]", "")
                // 将所有类型的空白（包括 NBSP）转为标准空格并修剪首尾
                .replace('\u00A0', ' ')
                .trim();
    }
}