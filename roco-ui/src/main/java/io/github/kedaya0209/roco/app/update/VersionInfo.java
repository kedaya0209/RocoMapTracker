package io.github.kedaya0209.roco.app.update;

import net.jcip.annotations.ThreadSafe;
import java.time.LocalDateTime;

/**
 * GitHub Release 版本信息
 */
@ThreadSafe
public record VersionInfo(
        String tagName,
        String version,
        String htmlUrl,
        String exeDownloadUrl,
        /** 完整安装包的 SHA256 校验文件 URL */
        String exeSha256Url,
        String patchDownloadUrl,
        /** 补丁的源版本号，如 "1.4.0"；null 表示不兼容旧格式补丁 */
        String patchFromVersion,
        /** 补丁的 SHA256 校验文件 URL */
        String patchSha256Url,
        LocalDateTime publishedAt,
        /** GitHub Release 更新说明 (body) */
        String releaseNotes
) {}
