package com.luoke.app.update;

import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;
import com.luoke.app.config.BuildConfig;

/**
 * 快速测试更新检测 — 不依赖 JavaFX 环境。
 * 直接运行 main 方法即可。
 */
@Slf4j
@ThreadSafe
public class UpdateTest {
    public static void main(String[] args) throws Exception {
        log.info("当前版本: {}", BuildConfig.APP_VERSION);

        UpdateChecker checker = new UpdateChecker();
        var result = checker.checkLatest();

        if (result.isEmpty()) {
            log.info("检查更新失败");
            return;
        }

        VersionInfo info = result.get();
        log.info("最新版本: {}", info.version());
        log.info("tag: {}", info.tagName());
        log.info("exe: {}", info.exeDownloadUrl());
        log.info("补丁: {}", info.patchDownloadUrl());
        log.info("补丁源版本: {}", info.patchFromVersion());
        log.info("exe sha256: {}", info.exeSha256Url());
        log.info("补丁 sha256: {}", info.patchSha256Url());
        log.info("发布时间: {}", info.publishedAt());
        log.info("是新版本? {}", UpdateChecker.isNewer(BuildConfig.APP_VERSION, info.version()));

        // 如果当前版本<远程版本，且补丁匹配，打印补丁信息
        if (UpdateChecker.isNewer(BuildConfig.APP_VERSION, info.version())) {
            if (info.patchDownloadUrl() != null
                    && info.patchFromVersion() != null
                    && info.patchFromVersion().equals(BuildConfig.APP_VERSION)) {
                log.info("补丁可用，将下载增量更新");
            } else {
                log.info("补丁不匹配，将下载完整 exe");
            }
        }
    }
}
