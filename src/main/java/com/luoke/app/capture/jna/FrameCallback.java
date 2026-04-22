package com.luoke.app.capture.jna;

import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

public interface FrameCallback extends StdCallLibrary.StdCallCallback {
    void onFrame(Pointer data, long len, int w, int h, int pitch, int code);
}