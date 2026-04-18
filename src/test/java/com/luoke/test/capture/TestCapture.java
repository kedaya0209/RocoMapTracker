package com.luoke.test.capture;

import com.luoke.capture.WindowsMonitor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class TestCapture {

    @Test
    public void test() throws InterruptedException, IOException, URISyntaxException {
        String parentDir = new File(TestCapture.class.getClassLoader().getResource("").toURI()).getAbsolutePath();
        String dirname = "capture-test";
        Path path = Files.createDirectories(Path.of(parentDir, dirname));
        String dir = path.toAbsolutePath().toString();
        WindowsMonitor monitor = new WindowsMonitor("洛克王国：世界");
        AtomicInteger count = new AtomicInteger(0);
        int sum = 10;
        CountDownLatch latch = new CountDownLatch(sum);
        monitor.startMonitor1(10, bufferedImage -> {
            ImageIO.write(bufferedImage, "png", new File(String.format("%s\\%d-test.png", dir, count.incrementAndGet())));
            latch.countDown();
            if (count.get() >= sum) {
                monitor.stopMonitor();
            }
        });
        latch.await();
    }
}
