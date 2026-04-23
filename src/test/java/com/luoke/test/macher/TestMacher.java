package com.luoke.test.macher;

import com.luoke.app.capture.WindowsMonitor;
import com.luoke.app.macher.map.MapMatcher;
import com.luoke.app.macher.map.MatchingResultUtil;
import com.luoke.app.macher.map.SiftMapMatcher;
import com.luoke.app.processor.MiniMapProcessor;
import com.luoke.test.capture.TestCapture;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class TestMacher {

    @Test
    public void testMacher() throws Exception {
        String parentDir = new File(TestCapture.class.getClassLoader().getResource("").toURI()).getAbsolutePath();
        String bigMap = "C:\\Users\\tangh\\Desktop\\code\\realTimePosition\\src\\main\\resources\\source\\big_map.png";
        String dirname = "macher-test";
        Path path = Files.createDirectories(Path.of(parentDir, dirname));
        String dir = path.toAbsolutePath().toString();
        WindowsMonitor monitor = new WindowsMonitor("洛克王国：世界");
        AtomicInteger count = new AtomicInteger(0);
        int sum = 1;
        CountDownLatch latch = new CountDownLatch(sum);
        MapMatcher matcher = new SiftMapMatcher();
        matcher.init(bigMap);
        monitor.startMonitor( frameRecord -> {
            int h = 0;
            byte[] bytes = MiniMapProcessor.extractFinalMiniMap(null, 0.897, 0.0785, 0.148).bytes();
            MatchingResultUtil.saveRawPixelsToFile(bytes, h, h, String.format("%s\\%d-test-miniMap.png", dir, count.get()));
            //获取小地图与大地图匹配的区域
            long start = System.currentTimeMillis();
            double[][] corners = matcher.match(bytes, h, h);
            log.info("匹配耗时: {}", System.currentTimeMillis() - start);
            //打印日志
            MatchingResultUtil.logCorners(corners);

            latch.countDown();
            if (count.get() >= sum) {
                monitor.stopMonitor();
            }
        });
        latch.await();
    }
}
