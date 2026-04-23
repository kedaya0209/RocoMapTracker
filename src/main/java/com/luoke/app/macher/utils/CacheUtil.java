package com.luoke.app.macher.utils;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import org.bytedeco.opencv.opencv_core.KeyPoint;
import org.bytedeco.opencv.opencv_core.KeyPointVector;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_core.CV_8U;

public class CacheUtil {

    // KeyPoint 序列化大小: 5个float(20b) + 2个int(8b) = 28字节
    private static final int KP_SIZE = 28;

    public static void saveFeatures(String path, Mat descriptors, KeyPointVector keypoints) {
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            try (OutputStream fos = Files.newOutputStream(file.toPath());
                 BufferedOutputStream bos = new BufferedOutputStream(fos, 128 * 1024);
                 ZstdOutputStream zos = new ZstdOutputStream(bos);
                 DataOutputStream dos = new DataOutputStream(zos)) {

                int type = descriptors.type() == CV_8U ? 0 : 1;
                int rows = descriptors.rows();
                int cols = descriptors.cols();
                dos.writeInt(type);
                dos.writeInt(rows);
                dos.writeInt(cols);

                // 1. 批量写入描述子数据 (核心优化)
                if (rows > 0 && cols > 0) {
                    long totalBytes = (long) rows * cols * (type == 1 ? 4 : 1);
                    byte[] data = new byte[(int) totalBytes];
                    descriptors.data().get(data); // 从原生内存一次性拷贝到 Java 堆
                    dos.write(data);
                }

                // 2. 批量写入关键点
                int kpCount = (int) keypoints.size();
                dos.writeInt(kpCount);
                if (kpCount > 0) {
                    ByteBuffer buffer = ByteBuffer.allocate(kpCount * KP_SIZE).order(ByteOrder.nativeOrder());
                    for (int i = 0; i < kpCount; i++) {
                        KeyPoint kp = keypoints.get(i);
                        buffer.putFloat(kp.pt().x());
                        buffer.putFloat(kp.pt().y());
                        buffer.putFloat(kp.size());
                        buffer.putFloat(kp.angle());
                        buffer.putFloat(kp.response());
                        buffer.putInt(kp.octave());
                        buffer.putInt(kp.class_id());
                    }
                    dos.write(buffer.array());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("保存缓存失败", e);
        }
    }

    public static boolean loadFeatures(String path, Mat descriptors, KeyPointVector keypoints) {
        File file = new File(path);
        if (!file.exists()) return false;

        try (InputStream fis = Files.newInputStream(file.toPath());
             BufferedInputStream bis = new BufferedInputStream(fis, 128 * 1024);
             ZstdInputStream zis = new ZstdInputStream(bis);
             DataInputStream dis = new DataInputStream(zis)) {

            int type = dis.readInt();
            int rows = dis.readInt();
            int cols = dis.readInt();

            // 1. 批量加载描述子
            descriptors.create(rows, cols, type == 1 ? CV_32F : CV_8U);
            if (rows > 0 && cols > 0) {
                byte[] data = new byte[rows * cols * (type == 1 ? 4 : 1)];
                dis.readFully(data);
                descriptors.data().put(data); // 从 Java 堆一次性拷贝回原生内存
            }

            // 2. 批量加载关键点
            int kpCount = dis.readInt();
            keypoints.resize(kpCount);
            if (kpCount > 0) {
                byte[] kpData = new byte[kpCount * KP_SIZE];
                dis.readFully(kpData);
                ByteBuffer buffer = ByteBuffer.wrap(kpData).order(ByteOrder.nativeOrder());
                for (int i = 0; i < kpCount; i++) {
                    KeyPoint kp = keypoints.get(i);
                    kp.pt().x(buffer.getFloat());
                    kp.pt().y(buffer.getFloat());
                    kp.size(buffer.getFloat());
                    kp.angle(buffer.getFloat());
                    kp.response(buffer.getFloat());
                    kp.octave(buffer.getInt());
                    kp.class_id(buffer.getInt());
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}