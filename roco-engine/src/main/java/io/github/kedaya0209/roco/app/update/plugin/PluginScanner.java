package io.github.kedaya0209.roco.app.update.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import io.github.kedaya0209.roco.app.utils.HashUtil;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件扫描器 - 扫描 plugins/{@literal *}/metadata.json, 解析并校验. 
 */
@Slf4j
@ThreadSafe
public class PluginScanner {

    private final ObjectMapper mapper;

    public PluginScanner() {
        this.mapper = new ObjectMapper();
    }

    /**
     * 扫描 plugins/ 目录下所有子目录中的 metadata.json. 
     *
     * @return 发现的插件列表(含状态)
     */
    public List<PluginInfo> scanPlugins() {
        File pluginsDir = FilePathUtil.getRelativeFile("plugins");
        return scanPlugins(pluginsDir);
    }

    /**
     * 扫描指定目录下的所有插件. 
     *
     * @param pluginsDir 插件根目录
     * @return 发现的插件列表(含状态)
     */
    public List<PluginInfo> scanPlugins(File pluginsDir) {
        List<PluginInfo> result = new ArrayList<>();
        if (!pluginsDir.isDirectory()) {
            log.info("插件目录不存在: {}", pluginsDir);
            return result;
        }

        File[] dirs = pluginsDir.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) {
            log.info("插件目录为空: {}", pluginsDir);
            return result;
        }

        for (File dir : dirs) {
            PluginInfo plugin = scanPlugin(dir);
            if (plugin != null) {
                result.add(plugin);
            }
        }
        return result;
    }

    /**
     * 扫描单个插件目录. 
     *
     * @param pluginDir 插件目录
     * @return PluginInfo, 失败返回 null
     */
    public PluginInfo scanPlugin(File pluginDir) {
        if (!pluginDir.isDirectory()) {
            return null;
        }

        File metaFile = new File(pluginDir, "metadata.json");
        if (!metaFile.isFile()) {
            log.debug("插件目录缺少 metadata.json: {}", pluginDir);
            return null;
        }

        try {
            JsonNode root = mapper.readTree(metaFile);
            PluginInfo info = parseMetadata(root, pluginDir.getName(), pluginDir);
            return validatePlugin(info);
        } catch (IOException e) {
            log.warn("解析 metadata.json 失败: {}", metaFile, e);
            return new PluginInfo(pluginDir.getName(), "", "", "", "", "",
                    "", null, List.of(), PluginStatus.UNKNOWN, pluginDir);
        }
    }

    private PluginInfo parseMetadata(JsonNode root, String id, File pluginDir) {
        String name = root.has("name") ? root.get("name").asText("") : "";
        String title = root.has("title") ? root.get("title").asText(name) : name;
        String version = root.has("version") ? root.get("version").asText("") : "";
        String entry = root.has("entry") ? root.get("entry").asText("") : "";
        String description = root.has("description") ? root.get("description").asText("") : "";
        String icon = root.has("icon") ? root.get("icon").asText("") : "";

        // 解析 source
        PluginSource source = null;
        if (root.has("source")) {
            JsonNode srcNode = root.get("source");
            String srcType = srcNode.has("type") ? srcNode.get("type").asText("") : "";
            String repo = srcNode.has("repo") ? srcNode.get("repo").asText("") : "";
            if (!srcType.isEmpty() && !repo.isEmpty()) {
                source = new PluginSource(srcType, repo);
            }
        }

        // 解析 assets
        List<PluginAsset> assets = new ArrayList<>();
        if (root.has("assets")) {
            JsonNode assetsNode = root.get("assets");
            if (assetsNode.isArray()) {
                for (JsonNode asset : assetsNode) {
                    String remoteName = asset.has("remoteName") ? asset.get("remoteName").asText("") : "";
                    String localPath = asset.has("localPath") ? asset.get("localPath").asText("") : "";
                    String sha256 = asset.has("sha256") ? asset.get("sha256").asText("") : "";
                    if (!remoteName.isEmpty()) {
                        assets.add(new PluginAsset(remoteName, localPath, sha256));
                    }
                }
            }
        }

        return new PluginInfo(id, name, title, version, description, icon, entry, source, assets,
                PluginStatus.UNKNOWN, pluginDir);
    }

    private PluginInfo validatePlugin(PluginInfo info) {
        if (info.assets().isEmpty()) {
            return new PluginInfo(info.id(), info.name(), info.title(), info.version(), info.description(),
                    info.icon(), info.entry(), info.source(), info.assets(), PluginStatus.NORMAL, info.pluginDir());
        }

        boolean allValid = true;
        for (PluginAsset asset : info.assets()) {
            String localPath = asset.localPath().isEmpty() ? asset.remoteName() : asset.localPath();
            File assetFile = new File(info.pluginDir(), localPath);
            if (!assetFile.isFile()) {
                log.warn("插件 {} 缺少文件: {}", info.id(), localPath);
                allValid = false;
                continue;
            }
            String actualSha256 = HashUtil.computeFileSHA256(assetFile);
            if (!actualSha256.equalsIgnoreCase(asset.sha256())) {
                log.warn("插件 {} 文件校验失败: {} (期望={}, 实际={})",
                        info.id(), localPath, asset.sha256(), actualSha256);
                allValid = false;
            }
        }

        PluginStatus status = allValid ? PluginStatus.NORMAL : PluginStatus.DAMAGED;
        return new PluginInfo(info.id(), info.name(), info.title(), info.version(), info.description(),
                info.icon(), info.entry(), info.source(), info.assets(), status, info.pluginDir());
    }
}
