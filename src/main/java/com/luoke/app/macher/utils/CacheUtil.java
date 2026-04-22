package com.luoke.app.macher.utils;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.KeyPoint;
import org.bytedeco.opencv.opencv_core.KeyPointVector;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.*;
import java.nio.file.Files;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_core.CV_8U;

public class CacheUtil {

    public static void saveFeatures(String path, Mat descriptors, KeyPointVector keypoints) {
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            try (OutputStream fos = Files.newOutputStream(file.toPath());
                 BufferedOutputStream bos = new BufferedOutputStream(fos);
                 ZstdOutputStream zos = new ZstdOutputStream(bos);
                 DataOutputStream dos = new DataOutputStream(zos)) {

                // 1. 保存类型：0=CV_8U(ORB), 1=CV_32F(SIFT)
                int type = descriptors.type() == CV_8U ? 0 : 1;
                dos.writeInt(type);
                dos.writeInt(descriptors.rows());
                dos.writeInt(descriptors.cols());

                if (!descriptors.empty()) {
                    if (type == 1) {
                        // SIFT
                        FloatIndexer idx = descriptors.createIndexer();
                        for (int r = 0; r < descriptors.rows(); r++) {
                            for (int c = 0; c < descriptors.cols(); c++) {
                                dos.writeFloat(idx.get(r, c));
                            }
                        }
                    } else {
                        // ORB
                        UByteIndexer idx = descriptors.createIndexer();
                        for (int r = 0; r < descriptors.rows(); r++) {
                            for (int c = 0; c < descriptors.cols(); c++) {
                                dos.writeByte(idx.get(r, c));
                            }
                        }
                    }
                }

                // 保存特征点
                int kpCount = (int) keypoints.size();
                dos.writeInt(kpCount);
                for (int i = 0; i < kpCount; i++) {
                    KeyPoint kp = keypoints.get(i);
                    dos.writeFloat(kp.pt().x());
                    dos.writeFloat(kp.pt().y());
                    dos.writeFloat(kp.size());
                    dos.writeFloat(kp.angle());
                    dos.writeFloat(kp.response());
                    dos.writeInt(kp.octave());
                    dos.writeInt(kp.class_id());
                }

            }
        } catch (Exception e) {
            throw new RuntimeException("保存缓存失败", e);
        }
    }

    public static boolean loadFeatures(String path, Mat descriptors, KeyPointVector keypoints) {
        try (InputStream fis = Files.newInputStream(new File(path).toPath());
             BufferedInputStream bis = new BufferedInputStream(fis);
             ZstdInputStream zis = new ZstdInputStream(bis);
             DataInputStream dis = new DataInputStream(zis)) {

            // 读取类型
            int type = dis.readInt();
            int dRows = dis.readInt();
            int dCols = dis.readInt();

            descriptors.release();
            if (type == 1) {
                descriptors.put(new Mat(dRows, dCols, CV_32F));
            } else {
                descriptors.put(new Mat(dRows, dCols, CV_8U));
            }

            if (!descriptors.empty()) {
                if (type == 1) {
                    FloatIndexer idx = descriptors.createIndexer();
                    for (int r = 0; r < dRows; r++) {
                        for (int c = 0; c < dCols; c++) {
                            idx.put(r, c, dis.readFloat());
                        }
                    }
                } else {
                    UByteIndexer idx = descriptors.createIndexer();
                    for (int r = 0; r < dRows; r++) {
                        for (int c = 0; c < dCols; c++) {
                            idx.put(r, c, dis.readByte());
                        }
                    }
                }
            }

            // 加载特征点
            int kpCount = dis.readInt();
            keypoints.resize(kpCount);
            for (int i = 0; i < kpCount; i++) {
                KeyPoint kp = keypoints.get(i);
                float x = dis.readFloat();
                float y = dis.readFloat();
                float size = dis.readFloat();
                float angle = dis.readFloat();
                float response = dis.readFloat();
                int octave = dis.readInt();
                int classId = dis.readInt();

                kp.pt().x(x);
                kp.pt().y(y);
                kp.size(size);
                kp.angle(angle);
                kp.response(response);
                kp.octave(octave);
                kp.class_id(classId);
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }
}