package io.github.kedaya0209.roco.app.update.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 插件更新检查器 - 调用 GitHub API 检查远程版本. 
 */
@Slf4j
@ThreadSafe
public class PluginUpdateChecker {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern ZIP_PATTERN = Pattern.compile(".+\\.zip$", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public PluginUpdateChecker() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * 检查指定插件是否有更新. 
     *
     * @param plugin 本地插件信息
     * @return 有更新时返回 PluginUpdateInfo
     */
    public Optional<PluginUpdateInfo> checkUpdate(PluginInfo plugin) {
        PluginSource source = plugin.source();
        if (source == null || !"github-release".equals(source.type())) {
            log.debug("插件 {} 无有效的远程源配置", plugin.id());
            return Optional.empty();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(source.apiUrl()))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "RocoMapTracker/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("检查插件 {} 更新失败, HTTP {}", plugin.id(), response.statusCode());
                return Optional.empty();
            }

            JsonNode root = mapper.readTree(response.body());
            return Optional.of(parseRelease(root, plugin));

        } catch (IOException e) {
            log.warn("检查插件 {} 更新失败", plugin.id(), e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("检查插件 {} 更新被中断", plugin.id(), e);
            return Optional.empty();
        }
    }

    private PluginUpdateInfo parseRelease(JsonNode root, PluginInfo plugin) {
        String tagName = root.get("tag_name").asText("");
        String body = root.get("body").asText("");
        String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;

        // 遍历 assets 找出 zip 包和其它资产
        String zipUrl = null;
        List<PluginAsset> remoteAssets = new ArrayList<>();

        JsonNode assets = root.get("assets");
        if (assets != null && assets.isArray()) {
            for (JsonNode asset : assets) {
                String name = asset.get("name").asText("");
                String url = asset.get("browser_download_url").asText("");

                if (ZIP_PATTERN.matcher(name).matches()) {
                    zipUrl = url;
                } else {
                    // 非 zip 文件作为独立资产
                    remoteAssets.add(new PluginAsset(name, name, ""));
                }
            }
        }

        return new PluginUpdateInfo(plugin.id(), version, tagName, zipUrl, body, remoteAssets);
    }

    /**
     * 比较两个版本号. 
     *
     * @param current 当前版本
     * @param latest  最新版本
     * @return latest > current 返回 true
     */
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
