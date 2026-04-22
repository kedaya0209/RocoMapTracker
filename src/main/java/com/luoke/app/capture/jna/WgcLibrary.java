package com.luoke.app.capture.jna;

import com.sun.jna.win32.StdCallLibrary;

public interface WgcLibrary extends StdCallLibrary {
    int init_capturer(long hwnd, int showBorder, FrameCallback callback);

    void destroy_capturer();
}