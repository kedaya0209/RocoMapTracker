package com.luoke.app.capture;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

/**
 * Windows窗口查找工具
 * 基于窗口标题匹配目标窗口
 */
public class WindowFinder {

    /**
     * 根据关键字查找窗口句柄
     * @param keyword 窗口标题关键字
     * @return 窗口句柄，未找到返回0
     */
    public static long findWindowByKeyword(String keyword) {
        final String target = cleanString(keyword);
        WinDef.HWND[] foundHwnd = new WinDef.HWND[1];

        User32.INSTANCE.EnumWindows((hwnd, pointer) -> {
            if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
                return true;
            }

            char[] windowText = new char[512];
            User32.INSTANCE.GetWindowText(hwnd, windowText, 512);
            String rawTitle = Native.toString(windowText);
            String cleanTitle = cleanString(rawTitle);

            if (cleanTitle.equals(target)) {
                foundHwnd[0] = hwnd;
                return false;
            }

            return true;
        }, null);

        return foundHwnd[0] == null ? 0 : Pointer.nativeValue(foundHwnd[0].getPointer());
    }

    /**
     * 清理字符串：移除控制字符、不可见字符及首尾空格
     * @param input 要清理的字符串
     * @return 清理后的字符串
     */
    private static String cleanString(String input) {
        if (input == null) return "";

        return input
                .replaceAll("\\p{Cntrl}", "")
                .replaceAll("[\\p{Cf}\\p{Zp}\\p{Zl}]", "")
                .replace('\u00A0', ' ')
                .trim();
    }
}
