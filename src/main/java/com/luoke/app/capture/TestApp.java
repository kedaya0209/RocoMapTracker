package com.luoke.app.capture;

import com.luoke.app.capture.processor.impl.MapMatcherProcessor;
import com.luoke.app.capture.processor.impl.OcrProcessor;

import java.util.ArrayList;
import java.util.Scanner;

public class TestApp {
    public static void main(String[] args) {
        // 1. 创建唯一的服务实例（对应 Rust 一个 ID）
        // 请确保游戏窗口已经打开，且标题匹配
        CaptureService mainService = new CaptureService("洛克王国：世界");

        // 2. 检查是否成功启动
        if (mainService.getId() <= 0) {
            System.err.println("无法启动采集任务，请检查窗口是否存在或 DLL 是否加载正确。");
            return;
        }

        MapMatcherProcessor siftProcessor = new MapMatcherProcessor(0, (s, color) -> {
            System.out.println(s);
        });

        OcrProcessor ocrProcessor = new OcrProcessor(1);

        // 3. 挂载处理器
        // 索引 0：保存测试图
        mainService.addProcessors(siftProcessor, ocrProcessor);
        ArrayList<ROIData> rois = new ArrayList<>();
        rois.add(siftProcessor.getRoi());
        rois.add(ocrProcessor.getRoi());

        ROIData[] roiArray = ROIData.createContiguousArray(rois);
        // 4. 一次性设置所有 ROI (万分比坐标)
        mainService.setRois(roiArray);

        System.out.println(">>> 采集服务已启动！ID: " + mainService.getId());
        System.out.println(">>> 输入 'stop' 并回车可以停止任务，输入 'exit' 退出程序。");

        // 5. 维持程序运行 (防止 main 线程直接结束)
        // 使用 Scanner 允许你在控制台手动输入指令来控制
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String line = scanner.nextLine();
            if ("stop".equalsIgnoreCase(line)) {
                mainService.stop();
                System.out.println("正在停止任务...");
            } else if ("exit".equalsIgnoreCase(line)) {
                mainService.stop();
                System.out.println("退出程序中...");
                break;
            }
        }

        // 稍微等待回调处理完最后的 code == -1
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
        }
        System.exit(0);
    }
}