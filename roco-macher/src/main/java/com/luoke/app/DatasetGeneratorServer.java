package com.luoke.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bytedeco.javacpp.indexer.Indexer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

public class DatasetGeneratorServer {

    private static final String BASE_PATH = "C:\\Users\\tangh\\Desktop\\dataset\\";
    private static final int PORT = 8080;

    public static void main(String[] args) throws Exception {
        // 创建目录结构
        Files.createDirectories(Paths.get(BASE_PATH + "train_set"));   // 纯净的训练图 (ROI)
        Files.createDirectories(Paths.get(BASE_PATH + "validation"));  // 带画线的验证图
        Files.createDirectories(Paths.get(BASE_PATH + "unlabeled"));   // 识别失败的记录

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/upload", new ImageHandler());
        server.setExecutor(null);
        System.out.println("数据标注服务端已启动，监听端口: " + PORT);
        server.start();
    }

    static class ImageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            byte[] bytes = exchange.getRequestBody().readAllBytes();
            Mat src = imdecode(new Mat(bytes), IMREAD_COLOR);

            if (src.empty()) {
                sendResponse(exchange, "Invalid Image Data", 400);
                return;
            }

            String result = processAndSave(src);
            sendResponse(exchange, result, 200);
        }

        private String processAndSave(Mat src) {
            int imgW = src.cols();
            int imgH = src.rows();

            // 1. 动态计算 ROI：以图片物理中心为中心，截取 64x64
            int roiW = 64;
            int roiH = 64;

            // 确保 ROI 不会超出原图边界（安全保护）
            int startX = Math.max(0, (imgW - roiW) / 2);
            int startY = Math.max(0, (imgH - roiH) / 2);
            int actualW = Math.min(roiW, imgW - startX);
            int actualH = Math.min(roiH, imgH - startY);

            Rect roiRect = new Rect(startX, startY, actualW, actualH);

            // 提取 ROI 副本
            Mat roiTrain = new Mat(src, roiRect).clone();
            Mat roiDebug = new Mat(src, roiRect).clone();

            // 计算当前 ROI 的逻辑中心（用于后续距离筛选）
            int roiCenterX = actualW / 2;
            int roiCenterY = actualH / 2;

            // 2. 颜色过滤
            Mat hsv = new Mat();
            cvtColor(roiTrain, hsv, COLOR_BGR2HSV);
            Mat mask = new Mat();
            inRange(hsv, new Mat(new Scalar(10, 200, 200, 0)),
                    new Mat(new Scalar(25, 255, 255, 0)), mask);

            MatVector contours = new MatVector();
            findContours(mask, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

            Mat targetContour = null;
            double minCenterDist = Double.MAX_VALUE;

            for (long i = 0; i < contours.size(); i++) {
                Mat cnt = contours.get(i);
                double area = contourArea(cnt);
                if (area < 50 || area > 600) continue;

                Moments m = moments(cnt);
                double cx = m.m10() / m.m00();
                double cy = m.m01() / m.m00();

                // 使用动态计算的中心点进行距离判定
                double dist = Math.sqrt(Math.pow(cx - roiCenterX, 2) + Math.pow(cy - roiCenterY, 2));

                if (dist < 50 && dist < minCenterDist) { // 稍微放宽中心判定范围到 50 像素
                    minCenterDist = dist;
                    targetContour = cnt;
                }
            }

            String id = UUID.randomUUID().toString().substring(0, 8);

            if (targetContour != null) {
                Moments m = moments(targetContour);
                double cx = m.m10() / m.m00();
                double cy = m.m01() / m.m00();

                // 寻找最远点作为尖端
                double maxDist = 0;
                double tipX = 0, tipY = 0;
                Indexer idx = targetContour.createIndexer();
                for (long j = 0; j < targetContour.total(); j++) {
                    double px = idx.getDouble(j, 0, 0);
                    double py = idx.getDouble(j, 0, 1);
                    double d = Math.pow(px - cx, 2) + Math.pow(py - cy, 2);
                    if (d > maxDist) {
                        maxDist = d;
                        tipX = px;
                        tipY = py;
                    }
                }

                int angle = (int) Math.toDegrees(Math.atan2(tipY - cy, tipX - cx));

                // 保存逻辑保持不变
                String trainName = angle + "_" + id + ".png";
                imwrite(BASE_PATH + "train_set\\" + trainName, roiTrain);

                drawContours(roiDebug, new MatVector(targetContour), -1, new Scalar(0, 255, 0, 0), 1, LINE_8, null, 0, null);
                line(roiDebug, new Point((int)cx, (int)cy), new Point((int)tipX, (int)tipY), new Scalar(255, 0, 0, 0), 2, LINE_AA, 0);
                imwrite(BASE_PATH + "validation\\" + trainName, roiDebug);

                return "Success: " + angle;
            } else {
                imwrite(BASE_PATH + "unlabeled\\" + id + ".png", roiTrain);
                return "Failed (No Arrow in ROI)";
            }
        }

        private void sendResponse(HttpExchange exchange, String response, int code) throws IOException {
            exchange.sendResponseHeaders(code, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
}