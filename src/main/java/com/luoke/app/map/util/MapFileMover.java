package com.luoke.app.map.util;

import com.luoke.app.config.AppConfig;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.utils.FileUtil;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;

/**
 * 地图文件移动器
 * <p>
 * 负责将下载的资源文件移动到外部资源目录。
 * 该类实现了以下核心功能：
 * <ul>
 *   <li>移动图标文件到外部资源目录</li>
 *   <li>移动点位配置文件到外部资源目录</li>
 *   <li>移动地图图片到外部资源目录</li>
 *   <li>统一的文件移动逻辑</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>使用Files.move进行原子性文件移动</li>
 *   <li>REPLACE_EXISTING选项覆盖已存在文件</li>
 *   <li>自动创建目标目录</li>
 *   <li>支持失败恢复，单个文件失败不影响其他文件</li>
 * </ul>
 * <p>
 * 移动流程：
 * <ol>
 *   <li>检查源目录是否存在</li>
 *   <li>自动创建目标目录</li>
 *   <li>遍历源目录所有文件</li>
 *   <li>移动每个文件到目标目录</li>
 *   <li>记录移动成功的日志</li>
 * </ol>
 * <p>
 * 目录结构：
 * <ul>
 *   <li>下载目录：相对路径，临时存储</li>
 *   <li>外部目录：绝对路径，应用使用</li>
 *   <li>解耦下载和应用目录，提高灵活性</li>
 * </ul>
 * <p>
 * 错误处理：
 * <ul>
 *   <li>单个文件移动失败不影响其他文件</li>
 *   <li>捕获所有异常，记录错误日志</li>
 *   <li>失败文件不会阻塞整个流程</li>
 * </ul>
 * <p>
 * Native资源管理：
 * <ul>
 *   <li>使用Files.move自动管理文件句柄</li>
 *   <li>避免手动管理导致资源泄漏</li>
 *   <li>确保文件正确关闭</li>
 * </ul>
 *
 * @author RocoMapTracker
 * @since 1.0
 */
@Slf4j
public class MapFileMover {

    // ====================== 下载完统一移动 ======================

    /**
     * 移动所有资源到外部目录
     * <p>
     * 该方法移动所有下载的资源文件：
     * <ul>
     *   <li>移动图标文件</li>
     *   <li>移动点位配置文件</li>
     *   <li>为应用准备运行时资源</li>
     * </ul>
     * <p>
     * 调用时机：
     * <ul>
     *   <li>下载完成后</li>
     *   <li>应用启动前</li>
     *   <li>确保资源就绪</li>
     * </ul>
     * <p>
     * 移动顺序：
     * <ul>
     *   <li>先移动图标，因为点位配置引用图标</li>
     *   <li>后移动点位配置</li>
     *   <li>确保依赖关系正确</li>
     * </ul>
     * <p>
     * 错误处理：
     * <ul>
     *   <li>单个移动失败不影响其他移动</li>
     *   <li>捕获所有异常，记录错误日志</li>
     * </ul>
     */
    public static void moveAllResources() {
        // 移动图标文件
        // 图标是基础资源，应该先移动
        moveIcons();

        // 移动点位配置文件
        // 点位配置引用图标，需要图标先就绪
        movePoints();
    }

    // ====================== 移动图标 → 外部资源目录 ======================

    /**
     * 移动图标文件到外部资源目录
     * <p>
     * 该方法将下载的图标文件移动到应用配置的外部资源目录：
     * <ul>
     *   <li>源：下载图标目录（相对路径）</li>
     *   <li>目标：应用配置的图标目录（绝对路径）</li>
     *   <li>自动创建目标目录</li>
     * </ul>
     * <p>
     * 目录说明：
     * <ul>
     *   <li>下载目录：临时存储，应用不使用</li>
   *   <li>外部目录：应用运行时使用，可配置</li>
     *   <li>解耦下载和运行，提高灵活性</li>
     * </ul>
     * <p>
     * 移动逻辑：
     * <ul>
     *   <li>遍历源目录所有图标文件</li>
     *   <li>移动到目标目录</li>
     *   <li>覆盖已存在文件</li>
     *   <li>记录移动成功日志</li>
     * </ul>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>下载完成后移动图标</li>
     *   <li>更新图标资源</li>
     *   <li>同步外部资源</li>
     * </ul>
     * <p>
     * 错误处理：
     * <ul>
     *   <li>单个图标移动失败不影响其他图标</li>
     *   <li>捕获所有异常，记录错误日志</li>
     * </ul>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>使用Files.move自动aring理文件句柄</li>
     *   <li>避免手动管理导致资源泄漏</li>
     * </ul>
     */
    public static void moveIcons() {
        // 下载来源：相对路径，临时存储
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_ICON_DIR);

        // 目标：从 AppConfig 读取图标目录
        // 使用绝对路径，应用运行时使用
        File dst = ResourceUtils.getExternalFile(AppConfig.ICON_DIR);

        // 如果目标目录不存在，自动创建
        if (!dst.exists()) dst.mkdirs();

        // 执行文件移动
        move(src, dst);
    }

    // ====================== 移动点位json → 外部资源目录 ======================

    /**
     * 移动点位配置文件到外部资源目录
     * <p>
     * 该方法将下载的点位配置文件移动到应用配置的外部资源目录：
     * <ul>
     *   <li>源：下载点位目录（相对路径）</li>
     *   <li>目标：应用配置的点位目录（绝对路径）</li>
     *   <li>自动创建目标目录</li>
     * </ul>
     * <p>
     * 目录说明：
     * <ul>
     *   <li>下载目录：临时存储，应用不使用</li>
   *   <li>外部目录：应用运行时使用，可配置</li>
     *   <li>包含resource_config.json等文件</li>
     * </ul>
     * <p>
     * 移动逻辑：
     * <ul>
     *   <li>遍历源目录所有配置文件</li>
     *   <li>移动到目标目录</li>
     *   <li>覆盖已存在文件</li>
     *   <li>记录移动成功日志</li>
     * </ul>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>下载完成后移动配置</li>
     *   <li>更新点位配置</li>
     *   <li>同步外部资源</li>
     * </ul>
     * <p>
     * 错误处理：
     * <ul>
     *   <li>单个文件移动失败不影响其他文件</li>
     *   <li>捕获所有异常，记录错误日志</li>
     * </ul>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>使用Files.move自动管理文件句柄</li>
     *   <li>避免手动管理导致资源泄漏</li>
     * </ul>
     */
    public static void movePoints() {
        // 下载来源：相对路径，临时存储
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_POINT_DIR);

        // 目标：从 AppConfig 读取点位目录
        // 使用绝对路径，应用运行时使用
        File dst = ResourceUtils.getExternalFile(AppConfig.RESOURCE_ICON_DIR);

        // 如果目标目录不存在，自动创建
        if (!dst.exists()) dst.mkdirs();

        // 执行文件移动
        move(src, dst);
    }

    // ====================== 移动地图 → 外部资源目录 ======================

    /**
     * 移动地图图片到外部资源目录
     * <p>
     * 该方法将拼接好的地图图片移动到应用配置的外部资源目录：
     * <ul>
     *   <li>源：下载地图目录（相对路径）</li>
     *   <li>目标：应用配置的地图资源目录（绝对路径）</li>
     *   <li>自动创建目标目录</li>
     * </ul>
     * <p>
     * 目录说明：
     * <ul>
     *   <li>下载目录：临时存储，应用不使用</li>
   *   <li>外部目录：应用运行时使用，可配置</li>
     *   <li>包含map_*.png等拼接好的地图文件</li>
     * </ul>
     * <p>
     * 移动逻辑：
     * <ul>
     *   <li>遍历源目录所有地图文件</li>
     *   <li>移动到目标目录</li>
     *   <li>覆盖已存在文件</li>
     *   <li>记录移动成功日志</li>
     * </ul>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>拼接完成后移动地图</li>
     *   <li>更新地图资源</li>
     *   <li>同步外部资源</li>
     * </ul>
     * <p>
     * 错误处理：
     * <ul>
     *   <li>单个文件移动失败不影响其他文件</li>
     *   <li>捕获所有异常，记录错误日志</li>
     * </ul>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>使用Files.move自动管理文件句柄</li>
     *   <li>避免手动管理导致资源泄漏</li>
     * </ul>
     */
    public static void moveMapsToResource() {
        // 下载来源：相对路径，临时存储
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_MAP_DIR);

        // 目标：从 AppConfig 读取地图资源路径
        // 使用绝对路径，应用运行时使用
        File dst = ResourceUtils.getExternalFile(AppConfig.MAP_RESOURCE_DIR);

        // 如果目标目录不存在，自动创建
        if (!dst.exists()) dst.mkdirs();

        // 执行文件移动
        move(src, dst);
    }

    // ====================== 通用移动 ======================

    /**
     * 通用文件移动方法
     * <p>
     * 该方法将源目录的所有文件移动到目标目录：
     * <ul>
     *   <li>检查源目录是否存在</li>
     *   <li>遍历源目录所有文件</li>
     *   <li>移动每个文件到目标目录</li>
     *   <li>覆盖已存在文件</li>
     * </ul>
     * <p>
     * 移动策略：
     * <ul>
     *   <li>使用Files.move进行原子性移动</li>
     *   <li>REPLACE_EXISTING选项覆盖已存在文件</li>
     *   <li>保持文件名不变</li>
     *   <li>失败不影响其他文件</li>
     * </ul>
     * <p>
     * 错误处理：
     * <ul>
     *   <li>单个文件移动失败不影响其他文件</li>
     *   <li>捕获所有异常，记录错误日志</li>
     *   <li>返回void，通过日志记录状态</li>
     * </ul>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>使用Files.move自动管理文件句柄</li>
     *   <li>避免手动管理导致资源泄漏</li>
     *   <li>确保文件正确关闭</li>
     * </ul>
     * <p>
     * 性能优化：
     * <ul>
     *   <li>原子性移动，高效可靠</li>
     *   <li>避免复制-删除的两步操作</li>
     *   <li>减少磁盘IO次数</li>
     * </ul>
     *
     * @param srcDir 源目录，待移动的文件所在目录
     * @param dstDir 目标目录，文件移动后的位置
     */
    private static void move(File srcDir, File dstDir) {
        // 检查源目录是否存在
    // 同时检查是否为空，避免NPE
        if (!srcDir.exists() || srcDir.listFiles() == null) return;

        // 遍历源目录所有文件
        for (File f : srcDir.listFiles()) {
            try {
                // 构建目标文件路径
                // 保持原文件名不变
                File to = new File(dstDir, f.getName());

                // 移动文件，REPLACE_EXISTING覆盖已存在文件
                // 使用Files.move进行原子性移动
                // 这种方法比复制-删除更高效
                Files.move(f.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // 记录移动成功的日志
                log.info("✅ 移动：{} → {}", f.getName(), dstDir);
            } catch (Exception e) {
                // 捕获异常，记录错误日志
                // 单个文件失败不影响其他文件
                log.error("❌ 移动失败：{}", f.getName(), e);
            }
        }
    }
}
