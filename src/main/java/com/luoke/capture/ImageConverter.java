package com.luoke.capture;

import java.awt.image.BufferedImage;

/**
 * BGRA 字节数组 转 BufferedImage
 */
public class ImageConverter {

    public static BufferedImage convertBgraToImage(byte[] bgraData, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int b = bgraData[index++] & 0xFF;
                int g = bgraData[index++] & 0xFF;
                int r = bgraData[index++] & 0xFF;
                int a = bgraData[index++] & 0xFF;

                int argb = (a << 24) | (r << 16) | (g << 8) | b;
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }
}