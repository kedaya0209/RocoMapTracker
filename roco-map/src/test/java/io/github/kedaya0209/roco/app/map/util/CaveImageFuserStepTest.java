package io.github.kedaya0209.roco.app.map.util;

import io.github.kedaya0209.roco.app.map.CaveImageEnhancer;
import io.github.kedaya0209.roco.app.map.CaveImageFuser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 逐步骤模拟 CaveImageFuser.fuse() 的完整流程，定位集成后失效的原因。
 *
 * <p>步骤：
 * 1. 复制 B1.png → temp dir（模拟原始下载文件）
 * 2. BrightnessExtractor 处理（阈值 50）→ _亮度提取.png
 * 3. 用透明图替换原图
 * 4. CLAHE 增强
 * 5. 检查透明像素保留情况
 * 6. 完整 fusing（含大陆图 G）
 */
public class CaveImageFuserStepTest {

    static final String MAPS_DIR = "D:\\Documents\\code\\Roco-tools\\resources\\source\\maps";
    static final String TEMP_DIR = "D:\\Documents\\code\\Roco-tools\\download\\maps";

    public static void main(String[] args) throws Exception {
        // 确保 temp 目录存在
        Files.createDirectories(Paths.get(TEMP_DIR));

        // 测试每张洞穴图
        String[] caves = {"B1.png", "B2.png", "G.png"};
        for (String name : caves) {
            System.out.println("\n========== 测试: " + name + " ==========");
            testFile(name);
        }
        System.out.println("\n全部测试完成");
    }

    static void testFile(String name) throws Exception {
        Path src = Paths.get(MAPS_DIR, name);
        Path work = Paths.get(TEMP_DIR, "test_" + name);
        // 复制到工作目录
        Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("原始文件: " + src + " (" + src.toFile().length() / 1024 + " KB)");
        System.out.println("工作文件: " + work + " (" + work.toFile().length() / 1024 + " KB)");

        // ===== Step 1: 读取原始 ARGB（和 CaveImageFuser 完全一致）=====
        BufferedImage caveImg = ImageIO.read(work.toFile());
        int w = caveImg.getWidth();
        int h = caveImg.getHeight();
        int n = w * h;
        int[] caveARGB = new int[n];
        caveImg.getRGB(0, 0, w, h, caveARGB, 0, w);
        printAlphaStats("Step0 原始读取", caveARGB, w, h);

        // ===== Step 2: BrightnessExtractor（阈值 50）=====
        Path brightPath = work.resolveSibling(work.getFileName().toString().replace(".png", "_亮度提取.png"));
        System.out.println("BrightnessExtractor 输出: " + brightPath.getFileName());
        BrightnessExtractor.extractAndSave(work, brightPath, 50);

        // 检查 _亮度提取.png 的透明情况
        BufferedImage brightImg = ImageIO.read(brightPath.toFile());
        int[] brightARGB = new int[n];
        brightImg.getRGB(0, 0, w, h, brightARGB, 0, w);
        printAlphaStats("Step2 亮度提取后", brightARGB, w, h);
        brightImg.flush();

        // ===== Step 3: 用透明图替换原图 =====
        brightImg = ImageIO.read(brightPath.toFile());
        brightImg.getRGB(0, 0, w, h, caveARGB, 0, w);
        ImageIO.write(brightImg, "png", work.toFile());
        brightImg.flush();
        printAlphaStats("Step3 写回后", caveARGB, w, h);

        // ===== Step 4: CLAHE 增强 =====
        CaveImageEnhancer.enhance(work);

        // 重新读取 CLAHE 后的像素
        BufferedImage enhancedImg = ImageIO.read(work.toFile());
        int[] enhancedARGB = new int[n];
        enhancedImg.getRGB(0, 0, w, h, enhancedARGB, 0, w);
        enhancedImg.flush();
        printAlphaStats("Step4 CLAHE后", enhancedARGB, w, h);

        // ===== 验证：透明像素是否被 CLAHE 破坏 =====
        int step0Transparent = countTransparent(caveARGB, n); // caveARGB 此时是 step3 的值
        int step4Transparent = countTransparent(enhancedARGB, n);
        System.out.println("\n验证: Step3 透明=" + step0Transparent + ", Step4 透明=" + step4Transparent);
        if (step4Transparent >= step0Transparent) {
            System.out.println("  ✓ CLAHE 没有破坏透明像素");
        } else {
            int lost = step0Transparent - step4Transparent;
            System.out.println("  ✗ CLAHE 破坏了 " + lost + " 个透明像素!");
        }

        // ===== Step 5: 完整 fusing（用 G.png 作为大陆图）=====
        if (!name.equals("G.png")) {
            System.out.println("\n--- 执行完整融合测试 ---");
            // 重新复制原文件（因为前序步骤覆盖了 work）
            Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);

            Path mainlandPath = Paths.get(MAPS_DIR, "G.png");
            BufferedImage mainlandInfo = ImageIO.read(mainlandPath.toFile());
            if (mainlandInfo.getWidth() != w || mainlandInfo.getHeight() != h) {
                System.out.println("跳过融合: 尺寸不匹配 G=" + mainlandInfo.getWidth() + "x" + mainlandInfo.getHeight()
                        + " vs " + name + "=" + w + "x" + h);
            } else {
                mainlandInfo.flush();
                CaveImageFuser.fuse(work, mainlandPath, 30, 0.3);
                BufferedImage fusedImg = ImageIO.read(work.toFile());
                int[] fusedARGB = new int[n];
                fusedImg.getRGB(0, 0, w, h, fusedARGB, 0, w);
                printAlphaStats("Step5 融合后", fusedARGB, w, h);
                fusedImg.flush();
            }
        }
    }

    static void printAlphaStats(String label, int[] argb, int w, int h) {
        int n = argb.length;
        int alpha0 = 0, alpha255 = 0, alphaOther = 0;
        for (int v : argb) {
            int a = (v >> 24) & 0xFF;
            if (a == 0) alpha0++;
            else if (a == 255) alpha255++;
            else alphaOther++;
        }
        System.out.printf("  %s: 透明=%,d (%.1f%%) 不透明=%,d (%.1f%%) 半透明=%,d (%.1f%%)%n",
                label, alpha0, 100.0 * alpha0 / n,
                alpha255, 100.0 * alpha255 / n,
                alphaOther, 100.0 * alphaOther / n);
    }

    static int countTransparent(int[] argb, int n) {
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (((argb[i] >> 24) & 0xFF) == 0) count++;
        }
        return count;
    }
}
