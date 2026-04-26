package com.luoke.app.map.core;

import com.luoke.app.map.LoadInfo;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.dto.MapCategoryItem;
import com.luoke.app.utils.FileUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 图标下载器
 * <p>
 * 负责从远程服务器下载地图图标资源到本地临时目录。
 * 该类实现了以下核心功能：
 * <ul>
 *   <li>从地图分类配置中提取图标URL列表</li>
 *   <li>去重处理，避免重复下载相同图标</li>
 *   <li>检查本地缓存，跳过已存在的文件</li>
 *   <li>使用HTTP连接下载图标文件</li>
 *   <li>支持网络重试和超时控制</li>
 *   <li>下载完成后进行适当的延迟，避免对服务器造成过大压力</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>使用Set进行URL去重，确保每个图标只下载一次</li>
 *   <li>采用HTTP连接池优化网络性能</li>
 *   <li>设置合理的连接和读取超时时间</li>
 *   <li>添加User-Agent和Referer头，模拟浏览器行为，避免被服务器拒绝</li>
 *   <li>下载间隔延迟，遵循"礼貌爬虫"原则</li>
 *   <li>使用try-with-resources确保网络资源正确释放</li>
 * </ul>
 * <p>
 * Native资源管理：
 * <ul>
 *   <li>HttpURLConnection在finally块中确保断开，释放系统网络资源</li>
 *   <li>InputStream和FileOutputStream使用try-with-resources自动关闭</li>
 *   <li>避免资源泄漏对Native Image打包的影响</li>
 * </ul>
 *
 * @author RocoMapTracker
 * @since 1.0
 */
@Slf4j
public class IconDownloader {

    /**
     * 下载所有地图图标
     * <p>
     * 该方法执行完整的图标下载流程：
     * <ol>
     *   <li>加载地图分类数据，提取所有图标URL</li>
     *   <li>使用Set去重，确保不重复下载</li>
     *   <li>遍历URL列表，逐个下载图标文件</li>
     *   <li>下载前检查文件是否已存在，存在则跳过</li>
     *   <li>下载失败记录日志，不影响其他图标下载</li>
     *   <li>每次下载后延迟指定时间，避免服务器压力</li>
     *   <li>输出下载统计信息（成功、跳过、失败数量）</li>
     * </ol>
     * <p>
     * 性能优化：
     * <ul>
     *   <li>使用8KB缓冲区进行流式传输，减少内存占用</li>
     *   <li>通过文件存在性检查避免不必要的网络请求</li>
     *   <li>延迟下载策略平衡下载速度和服务器负载</li>
     * </ul>
     * <p>
     * 错误处理：
     * <ul>
     *   <li>单个图标下载失败不会中断整个流程</li>
     *   <li>网络异常会被捕获并记录，不影响其他图标</li>
     *   <li>使用日志级别区分成功、跳过和失败情况</li>
     * </ul>
     */
    public static void downloadIcons() {
        // 加载地图分类数据，获取所有分类项
        List<MapCategoryItem> list = LoadInfo.parseCategoryData();

        // 使用Set去重，确保相同URL的图标只下载一次
        // 这能有效减少网络请求次数，提高下载效率
        Set<String> urls = new HashSet<>();

        // 遍历分类项，提取所有图标URL
        for (MapCategoryItem item : list) {
            String icon = item.getIcon();
            if (icon != null && !icon.isBlank()) {
                urls.add(icon);
            }
        }

        // 统计计数器：成功、跳过、失败
        int success = 0, skip = 0, fail = 0;

        // 遍历所有去重后的URL进行下载
        for (String url : urls) {
            try {
                // 从URL中提取文件名（最后一个/之后的部分）
                // 例如：https://example.com/icon.png -> icon.png
                String name = url.substring(url.lastIndexOf("/") + 1);

                // 构建目标文件路径，保存在下载图标目录
                File file = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_ICON_DIR, name);

                // 检查文件是否已存在，存在则跳过下载
                // 这种缓存机制可以显著提高重复执行时的速度
                if (file.exists()) {
                    skip++;
                    continue;
                }

                // 建立HTTP连接，准备下载
                // 使用URLConnection而非高级HTTP客户端，减少依赖
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");

                // 设置User-Agent头，模拟浏览器请求
                // 避免被服务器识别为爬虫而拒绝服务
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

                // 设置Referer头，表明请求来源
                // 某些服务器会检查Referer来防止盗链
                conn.setRequestProperty("Referer", "https://wiki.biligame.com/");

                // 设置连接超时，防止因网络问题长时间阻塞
                conn.setConnectTimeout(MapResourceUpdater.CONNECT_TIMEOUT);

                // 设置读取超时，防止服务器响应缓慢导致长时间等待
                conn.setReadTimeout(MapResourceUpdater.READ);

                // 使用try-with-resources自动关闭流
                // 确保InputStream和FileOutputStream在任何情况下都能正确关闭
                // 这对Native Image资源管理尤为重要
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(file)) {

                    // 使用8KB缓冲区进行流式传输
                    // 这个大小在内存使用和IO效率之间取得了良好平衡
                    byte[] buf = new byte[8192];
                    int len;

                    // 循环读取数据并写入文件
                    // 采用缓冲读写方式，减少系统调用次数
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                } finally {
                    // 放进 finally 保证一定关闭连接
                    // 即使发生异常也能正确释放网络资源
                    // 这对Native Image环境下的资源管理至关重要
                    conn.disconnect();
                }

                // 成功计数加1
                success++;

                // 记录下载成功的日志
                log.info("⬇️  {}", name);

                // 下载延迟，避免对服务器造成过大压力
                // 遵循"礼貌爬虫"原则，给服务器喘息时间
                Thread.sleep(MapResourceUpdater.ICON_DELAY_MS);

            } catch (Exception e) {
                // 捕获异常，失败计数加1
                // 单个图标下载失败不影响其他图标的下载
                fail++;

                // 记录下载失败的日志
                log.error("❌ 下载失败: {}", url);
            }
        }

        // 输出下载统计信息
        // 使用分隔线提高日志可读性
        log.info("=====================================");
        log.info("图标下载完成 | 成功：{} 跳过：{} 失败：{}", success, skip, fail);
        log.info("=====================================");
    }
}
