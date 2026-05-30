package io.github.kedaya0209.roco.app.update.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import io.github.kedaya0209.roco.app.utils.HashUtil;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

import io.github.kedaya0209.roco.app.config.UpdateConfig;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 插件下载管理器 - 下载 zip, 解压, 校验 sha256, 替换插件目录. 
 */
@Slf4j
@NotThreadSafe
public class PluginDownloadManager {

    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final int MAX_RETRIES = 3;
    private static final int BUF_SIZE = 8192;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public PluginDownloadManager() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * 下载并安装插件更新. 
     *
     * @param update    更新信息
     * @param progress  进度回调 (0.0 ~ 1.0)
     * @param onSuccess 成功后回调
     * @param onError   失败后回调
     */
    public void downloadAndInstall(PluginUpdateInfo update,
                                   Consumer<Double> progress,
                                   Runnable onSuccess,
                                   Consumer<String> onError) {
        try {
            // 下载到 plugins/download/{pluginId}.zip，用户可随时删除
            File downloadDir = FilePathUtil.getRelativeFile("plugins", "download");
            Files.createDirectories(downloadDir.toPath());
            Path zipPath = downloadDir.toPath().resolve(update.pluginId() + ".zip");

            progress.accept(0.0);
            downloadFile(update.zipDownloadUrl(), zipPath, p -> progress.accept(p * 0.85));

            Path tempDir = Files.createTempDirectory("plugin-extract-" + update.pluginId());
            try {
                extractZip(zipPath, tempDir);
                progress.accept(0.9);

                // zip 内可能有一层顶层目录(如 sniffer/)
                File extractRoot = findContentDir(tempDir.toFile());

                // 验证 sha256
                PluginInfo extractedInfo = validateExtracted(extractRoot);
                if (extractedInfo == null) {
                    onError.accept("插件包验证失败, 文件可能已损坏");
                    return;
                }

                progress.accept(0.95);

                // 替换插件目录
                File targetDir = FilePathUtil.getRelativeFile("plugins", update.pluginId());
                replaceWith(extractRoot, targetDir);

                progress.accept(1.0);
                onSuccess.run();

            } finally {
                deleteRecursively(tempDir);
            }
            // zip 保留在 plugins/download/ 中，不删除
        } catch (Exception e) {
            log.error("插件 {} 下载安装失败", update.pluginId(), e);
            onError.accept("下载安装失败: " + e.getMessage());
        }
    }

    private void downloadFile(String url, Path targetPath, Consumer<Double> progressCallback)
            throws IOException, InterruptedException {
        // 代理链接前缀
        String proxyUrl = UpdateConfig.PROXY_URL;
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            url = proxyUrl + url;
        }
        Path parent = targetPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        log.info(url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        HttpResponse<InputStream> response = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw new IOException("下载失败, HTTP " + response.statusCode());
                }
                break;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES) {
                    long delay = (long) Math.pow(2, attempt) * 1000L;
                    log.warn("下载失败(第 {}/{} 次), {} 秒后重试", attempt, MAX_RETRIES, delay / 1000);
                    Thread.sleep(delay);
                } else {
                    throw e;
                }
            }
        }

        long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        long bytesReadSoFar = 0;
        long lastUpdateTime = 0;

        try (InputStream is = response.body();
             OutputStream os = new BufferedOutputStream(new FileOutputStream(targetPath.toFile()))) {
            byte[] buffer = new byte[BUF_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                bytesReadSoFar += bytesRead;
                if (totalBytes > 0 && progressCallback != null) {
                    long now = System.nanoTime();
                    if (now - lastUpdateTime >= 50_000_000L || bytesReadSoFar == totalBytes) {
                        lastUpdateTime = now;
                        progressCallback.accept((double) bytesReadSoFar / totalBytes);
                    }
                }
            }
            os.flush();
        }
    }

    private void extractZip(Path zipPath, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(targetDir.normalize())) {
                    throw new IOException("Zip 路径越界: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream os = new FileOutputStream(entryPath.toFile())) {
                        byte[] buffer = new byte[BUF_SIZE];
                        int len;
                        while ((len = zis.read(buffer)) != -1) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * 解压后的目录可能包含一层顶层目录, 将其展平. 
     */
    private File findContentDir(File extractDir) {
        File[] files = extractDir.listFiles();
        if (files != null && files.length == 1 && files[0].isDirectory()) {
            return files[0];
        }
        return extractDir;
    }

    /**
     * 验证解压后的插件包：读取 metadata.json → 校验每个文件 sha256. 
     *
     * @return 验证通过返回 PluginInfo, 失败返回 null
     */
    private PluginInfo validateExtracted(File dir) {
        File metaFile = new File(dir, "metadata.json");
        if (!metaFile.isFile()) {
            log.error("插件包缺少 metadata.json");
            return null;
        }

        try {
            JsonNode root = mapper.readTree(metaFile);
            String version = root.has("version") ? root.get("version").asText("") : "";

            List<PluginAsset> assets = new ArrayList<>();
            if (root.has("assets")) {
                for (JsonNode node : root.get("assets")) {
                    String localPath = node.has("localPath") ? node.get("localPath").asText("") : "";
                    String remoteName = node.has("remoteName") ? node.get("remoteName").asText("") : "";
                    String sha256 = node.has("sha256") ? node.get("sha256").asText("") : "";
                    if (localPath.isEmpty()) localPath = remoteName;
                    if (localPath.isEmpty()) continue;

                    File assetFile = new File(dir, localPath);
                    if (!assetFile.isFile()) {
                        log.error("缺少文件: {}", localPath);
                        return null;
                    }

                    String actual = HashUtil.computeFileSHA256(assetFile);
                    if (!sha256.isEmpty() && !actual.equalsIgnoreCase(sha256)) {
                        log.error("sha256 校验失败: {} 期望={} 实际={}", localPath, sha256, actual);
                        return null;
                    }
                    assets.add(new PluginAsset(remoteName, localPath, actual));
                }
            }

            // 重新写入 metadata.json(确保 sha256 为最新计算值)
            writeMetadata(metaFile, root, version, assets);
            return new PluginInfo(dir.getName(), "", "", version, "", "", "", null, assets,
                    PluginStatus.NORMAL, dir);

        } catch (IOException e) {
            log.error("验证插件包失败", e);
            return null;
        }
    }

    private void writeMetadata(File metaFile, JsonNode oldRoot, String version, List<PluginAsset> assets) throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode meta = mapper.createObjectNode();
        meta.put("version", version);
        if (oldRoot.has("name")) meta.put("name", oldRoot.get("name").asText());
        if (oldRoot.has("title")) meta.put("title", oldRoot.get("title").asText());
        if (oldRoot.has("description")) meta.put("description", oldRoot.get("description").asText());
        if (oldRoot.has("entry")) meta.put("entry", oldRoot.get("entry").asText());
        if (oldRoot.has("icon")) meta.put("icon", oldRoot.get("icon").asText());
        if (oldRoot.has("source")) meta.set("source", oldRoot.get("source"));

        com.fasterxml.jackson.databind.node.ArrayNode assetsArr = meta.putArray("assets");
        for (PluginAsset a : assets) {
            com.fasterxml.jackson.databind.node.ObjectNode assetObj = assetsArr.addObject();
            assetObj.put("remoteName", a.remoteName());
            assetObj.put("localPath", a.localPath());
            assetObj.put("sha256", a.sha256());
        }

        mapper.writerWithDefaultPrettyPrinter().writeValue(metaFile, meta);
    }

    /**
     * 用新目录替换旧插件目录. 
     */
    private void replaceWith(File sourceDir, File targetDir) throws IOException {
        Path srcPath = sourceDir.toPath();
        Path tgtPath = targetDir.toPath();

        // 删除旧目录
        if (Files.exists(tgtPath)) {
            deleteRecursively(tgtPath);
        }
        Files.createDirectories(tgtPath.getParent());

        // 复制新文件
        try (var stream = Files.walk(srcPath)) {
            stream.forEach(source -> {
                try {
                    Path target = tgtPath.resolve(srcPath.relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    log.warn("复制文件失败: {}", source, e);
                }
            });
        }

        log.info("插件目录已替换: {}", targetDir);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("删除失败: {}", p, e);
                        }
                    });
        }
    }
}
