package io.github.kedaya0209.roco.app.update;

import net.jcip.annotations.ThreadSafe;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.kedaya0209.roco.app.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * GitHub Release 更新检查器。
 */
@Slf4j
@ThreadSafe
public class UpdateChecker {

    private static final String GITHUB_API = "https://api.github.com/repos/kedaya0209/RocoMapTracker/releases/latest";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;

    public UpdateChecker() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Optional<VersionInfo> checkLatest() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_API))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "RocoMapTracker/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("GitHub API 返回状态码: {}", response.statusCode());
                return Optional.empty();
            }

            JsonNode root = JsonUtils.getMapper().readTree(response.body());
            return Optional.of(parseRelease(root));

        } catch (IOException e) {
            log.warn("检查更新失败", e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("更新检查被中断", e);
            return Optional.empty();
        }
    }

    private VersionInfo parseRelease(JsonNode root) {
        String tagName = root.get("tag_name").asText("");
        String htmlUrl = root.get("html_url").asText("");
        String publishedAt = root.get("published_at").asText("");
        String body = root.get("body").asText("");

        LocalDateTime dateTime = null;
        if (!publishedAt.isEmpty()) {
            try {
                dateTime = LocalDateTime.parse(publishedAt, DateTimeFormatter.ISO_DATE_TIME);
            } catch (DateTimeParseException e) {
                log.warn("解析 published_at 失败: {}", publishedAt, e);
            }
        }

        String exeUrl = null;
        String exeSha256Url = null;
        String patchUrl = null;
        String patchSha256Url = null;
        String patchFromVersion = null;
        Pattern patchPattern = Pattern.compile(
                "RocoMapTracker-v?([\\d.]+)-to-v[\\d.]+\\.hdiff");
        JsonNode assets = root.get("assets");
        if (assets != null && assets.isArray()) {
            for (JsonNode asset : assets) {
                String name = asset.get("name").asText("");
                String url = asset.get("browser_download_url").asText("");
                if (name.endsWith(".exe.sha256")) {
                    exeSha256Url = url;
                } else if (name.endsWith(".hdiff.sha256")) {
                    patchSha256Url = url;
                } else if (name.endsWith(".exe")) {
                    exeUrl = url;
                } else if (name.endsWith(".hdiff")) {
                    patchUrl = url;
                    var m = patchPattern.matcher(name);
                    if (m.matches()) {
                        patchFromVersion = m.group(1);
                    }
                }
            }
        }

        String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;
        return new VersionInfo(tagName, version, htmlUrl, exeUrl, exeSha256Url,
                patchUrl, patchFromVersion, patchSha256Url, dateTime, body);
    }

    public static boolean isNewer(String current, String latest) {
        if (current == null || latest == null) return false;
        if (current.equals(latest)) return false;

        String[] curParts = current.split("\\.");
        String[] latParts = latest.split("\\.");

        int len = Math.max(curParts.length, latParts.length);
        for (int i = 0; i < len; i++) {
            int curNum = i < curParts.length ? parseIntSafe(curParts[i]) : 0;
            int latNum = i < latParts.length ? parseIntSafe(latParts[i]) : 0;
            if (latNum > curNum) return true;
            if (latNum < curNum) return false;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
