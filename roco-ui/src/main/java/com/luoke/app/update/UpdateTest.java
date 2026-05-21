package com.luoke.app.update;

import net.jcip.annotations.ThreadSafe;
import com.luoke.app.config.BuildConfig;

/**
 * 快速测试更新检测 — 不依赖 JavaFX 环境。
 * 直接运行 main 方法即可。
 */
@ThreadSafe
public class UpdateTest {
    public static void main(String[] args) throws Exception {
        System.out.println("当前版本: " + BuildConfig.APP_VERSION);

        UpdateChecker checker = new UpdateChecker();
        var result = checker.checkLatest();

        if (result.isEmpty()) {
            System.out.println("检查更新失败");
            return;
        }

        VersionInfo info = result.get();
        System.out.println("最新版本: " + info.version());
        System.out.println("tag: " + info.tagName());
        System.out.println("exe: " + info.exeDownloadUrl());
        System.out.println("补丁: " + info.patchDownloadUrl());
        System.out.println("补丁源版本: " + info.patchFromVersion());
        System.out.println("exe sha256: " + info.exeSha256Url());
        System.out.println("补丁 sha256: " + info.patchSha256Url());
        System.out.println("发布时间: " + info.publishedAt());
        System.out.println("是新版本? " + UpdateChecker.isNewer(BuildConfig.APP_VERSION, info.version()));

        // 如果当前版本<远程版本，且补丁匹配，打印补丁信息
        if (UpdateChecker.isNewer(BuildConfig.APP_VERSION, info.version())) {
            if (info.patchDownloadUrl() != null
                    && info.patchFromVersion() != null
                    && info.patchFromVersion().equals(BuildConfig.APP_VERSION)) {
                System.out.println("\n✅ 补丁可用，将下载增量更新");
            } else {
                System.out.println("\n⚠️ 补丁不匹配，将下载完整 exe");
            }
        }
    }
}
