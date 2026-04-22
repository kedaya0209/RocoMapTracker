package com.luoke.app.capture.jna;

import com.sun.jna.Pointer;

public record Frame(Pointer data, int width, int height, int pitch) {

    public byte[] getPixels() {
        // 按 pitch 正确读取
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            byte[] row = data.getByteArray((long) y * pitch, width * 4);
            System.arraycopy(row, 0, pixels, y * width * 4, row.length);
        }
        return pixels;
    }
}