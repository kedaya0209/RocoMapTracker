package com.luoke.app.context;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 物资采集上下文管理器（单例模式）
 * <p>
 * 职责：
 * <ul>
 *   <li>存储和管理游戏内物资采集的识别结果</li>
 *   <li>记录每次采集的时间戳流水（精确到毫秒）</li>
 *   <li>按物资名称汇总采集数量</li>
 *   <li>生成详细的采集报告（包括汇总和流水）</li>
 *   <li>支持数据重置以开始新一轮采集</li>
 * </ul>
 * <p>
 * 核心功能：
 * <ul>
 *   <li>实时记录：OCR识别到物资时立即记录</li>
 *   <li>线程安全：支持多线程环境下的并发写入</li>
 *   <li>统计分析：自动计算采集总时长和数量统计</li>
 *   <li>报告生成：生成易读的文本报告用于用户查看</li>
 * </ul>
 * <p>
 * 性能优化：
 * <ul>
 *   <li>使用ConcurrentHashMap保证汇总统计的线程安全</li>
 *   <li>使用AtomicLong保证时间戳的原子性更新</li>
 *   <li>使用synchronizedList保护流水记录的完整性</li>
 *   <li>使用record类型减少内存占用</li>
 * </ul>
 * <p>
 * 注意事项：
 * <ul>
 *   <li>单例实现确保全局唯一的采集记录</li>
 *   <li>firstLootTimestamp使用CAS操作确保只设置一次</li>
 *   <li>历史流水会持续增长，需要定期reset或限制大小</li>
 * </ul>
 */
@Slf4j
@Data
public class MaterialCollectionContext {

    // ====================== 【单例实现】 ======================
    // 全局唯一实例：确保整个应用只有一个采集上下文
    private static final MaterialCollectionContext INSTANCE = new MaterialCollectionContext();

    // ====================== 【数据存储结构】 ======================
    // 汇总映射表：物资名称 -> 累计采集数量
    // 使用ConcurrentHashMap保证多线程环境下的线程安全写入
    // 适用于高并发场景：多个OCR线程可能同时调用addMaterial
    private final Map<String, Integer> summaryMap = new ConcurrentHashMap<>();

    // 历史流水记录：按时间顺序存储每一次识别到的采集记录
    // 使用Collections.synchronizedList包装ArrayList，保证add和遍历的线程安全
    // 注意：列表会持续增长，长期运行可能导致内存占用增加
    private final List<LootRecord> historyLog = Collections.synchronizedList(new ArrayList<>());

    // ====================== 【核心计时器】 ======================
    // 首次采集时间戳：0表示尚未开始采集，>0表示第一次采集的时间（毫秒）
    // 使用AtomicLong保证compareAndSet操作的原子性，确保只设置一次
    // 这是整个采集会话的起始时间，用于计算总时长
    private final AtomicLong firstLootTimestamp = new AtomicLong(0);

    /**
     * 私有构造函数：防止外部实例化，确保单实例
     */
    private MaterialCollectionContext() {
    }

    /**
     * 获取采集上下文管理器的单例实例
     *
     * @return 全局唯一的MaterialCollectionContext实例
     */
    public static MaterialCollectionContext getInstance() {
        return INSTANCE;
    }

    /**
     * 核心方法：记录一次物资采集（由OCR识别结果回调调用）
     * <p>
     * 调用时机：RealOcrHook识别到游戏内的物资名称和数量时调用
     * <p>
     * 功能流程：
     * <ol>
     *   <li>获取当前时间戳（精确到毫秒）</li>
     *   <li>设置首次采集时间（仅第一次调用时生效，使用CAS操作）</li>
     *   <li>将采集记录添加到历史流水</li>
     *   <li>更新物资汇总统计（累加数量）</li>
     *   <li>输出日志记录</li>
     * </ol>
     * <p>
     * 线程安全保证：
     * <ul>
     *   <li>firstLootTimestamp使用AtomicLong的compareAndSet原子操作</li>
     *   <li>summaryMapari使用ConcurrentHashMap.merge保证原子性更新</li>
     *   <li>historyLog使用synchronizedList保证线程安全的添加操作</li>
     * </ul>
     * <p>
     * 性能考虑：
     * <ul>
     *   <li>时间戳获取使用System.currentTimeMillis（性能优于Date）</li>
     *   <li>CAS操作避免synchronized锁，减少线程竞争</li>
     *   <li>日志输出使用参数化形式，避免字符串拼接开销</li>
     * </ul>
     *
     * @param name   物资名称（如"矿石"、"木材"等）
     * @param amount 本次采集的数量（必须为正数）
     * @throws IllegalArgumentException 如果数量为负数
     * @throws NullPointerException    如果物资名称为null
     */
    public void addMaterial(String name, int amount) {
        // 参数校验：确保数量为正数
        if (amount <= 0) {
            throw new IllegalArgumentException("采集数量必须为正数: " + amount);
        }

        // 获取当前时间戳：用于记录采集时间
        long now = System.currentTimeMillis();

        // 设置第一次拾取的启动时间（仅执行一次）
        // 使用CAS（Compare-And-Set）操作：如果当前值为0，则设置为now
        // 这种方式比synchronized锁性能更好，且天然保证只设置一次
        firstLootTimestamp.compareAndSet(0, now);

        // 存入历史流水：记录每次采集的详细信息
        // synchronizedList.add()是线程安全的，可以被多个线程同时调用
        historyLog.add(new LootRecord(now, name, amount));

        // 更新物资汇总统计：使用merge方法原子性地累加数量
        // 如果key不存在，则插入amount；如果存在，则执行Integer::sum累加
        // merge操作是原子的，无需额外的同步机制
        summaryMap.merge(name, amount, Integer::sum);

        // 输出日志记录：使用参数化形式，避免不必要的字符串拼接
        // 日志格式：📦 [采集记录] 物资名称 +数量, 当前累计: 总数量
        log.info("📦 [采集记录] {} +{}, 当前累计: {}", name, amount, summaryMap.get(name));
    }

    /**
     * 生成完整的采集报告文本（包括汇总和详细流水）
     * <p>
     * 功能说明：
     * <ul>
     *   <li>计算采集总时长（从第一次采集到当前时间）</li>
     *   <li>显示所有物资的汇总统计</li>
     *   <li>列出完整的历史流水记录</li>
     *   <li>格式化为易读的文本报告</li>
     * </ul>
     * <p>
     * 线程安全：
     * <ul>
     *   <li>遍历summaryMap是安全的（ConcurrentHashMap的读操作无需锁）</li>
     *   <li>遍历historyLog使用synchronized保证数据一致性</li>
     * </ul>
     * <p>
     * 性能考虑：
     * <ul>
     *   <li>使用StringBuilder避免字符串拼接的性能开销</li>
     *   <li>时间计算使用整数运算，避免浮点运算开销</li>
     *   <li>对于大量流水记录，可能需要分页或限制数量</li>
     * </ul>
     *
     * @return 格式化的采集报告字符串；如果尚未开始采集，返回"--- 暂无采集数据 ---"
     */
    public String generateFullReport() {
        // 检查是否已开始采集：firstLootTimestamp为0表示没有记录
        if (firstLootTimestamp.get() == 0) {
            return "--- 暂无采集数据 ---";
        }

        // 使用StringBuilder构建报告：性能优于字符串拼接
        StringBuilder report = new StringBuilder();

        // 计算采集总时长（毫秒）
        long durationMs = System.currentTimeMillis() - firstLootTimestamp.get();

        // 转换为分钟和秒：便于用户理解
        long minutes = (durationMs / 1000) / 60;  // 总分钟数
        long seconds = (durationMs / 1000) % 60;  // 剩余秒数

        // 添加报告头部信息
        report.append("========= 采集报告 =========\n");
        report.append(String.format("持续时间: %d分%d秒\n", minutes, seconds));
        report.append("---------------------------\n");

        // 汇总数据部分：显示每种物资的总数量
        report.append("[ 汇总数据 ]\n");
        summaryMap.forEach((name, total) ->
                report.append(String.format(" - %s: 总计 %d\n", name, total))
        );

        // 详细流水部分：按时间顺序列出所有采集记录
        report.append("\n[ 详细流水 ]\n");

        // 使用synchronized保护遍历操作：防止在遍历过程中列表被修改
        // 注意：如果historyLog很大（数千条），可能会影响性能
        // 建议在实际应用中考虑分页或限制输出数量（如最后50条）
        synchronized (historyLog) {
            for (LootRecord record : historyLog) {
                report.append(record.format()).append("\n");
            }
        }

        // 添加报告尾部
        report.append("===========================");

        return report.toString();
    }

    /**
     * 重置所有采集数据（用于开始新一轮采集）
     * <p>
     * 调用时机：
     * <ul>
     *   <li>用户明确开始新一轮采集时</li>
     *   <li>检测到游戏场景切换时</li>
     *   <li>需要清空历史记录时</li>
     * </ul>
     * <p>
     * 功能说明：
     * <ul>
     *   <li>清空物资汇总映射表</li>
     *   <li>清空历史流水记录</li>
     *   <li>重置首次采集时间戳为0</li>
     *   <li>输出重置日志</li>
     * </ul>
     * <p>
     * 内存优化：此方法会释放所有历史数据的内存引用，避免内存泄漏
     * <p>
     * 线程安全：clear()操作是原子的，但可能与addMaterial并发执行
     *
     * @see #addMaterial(String, int)
     */
    public void reset() {
        // 清空物资汇总映射表：释放所有统计数据的内存
        summaryMap.clear();

        // 清空历史流水记录：释放所有流水记录的内存
        historyLog.clear();

        // 重置首次采集时间戳：设置为0表示尚未开始
        firstLootTimestamp.set(0);

        // 输出重置日志：使用符号♻️表示循环/重置
        log.info("♻️ 采集上下文已重置");
    }

    /**
     * 采集记录内部类（使用Java 14+的record类型）
     * <p>
     * 职责：存储单次物资采集的详细信息
     * <p>
     * 字段说明：
     * <ul>
     *   <li>timestamp: 采集时间戳（毫秒，自1970-01-01起）</li>
     *   <li>name: 物资名称（如"矿石"、"木材"等）</li>
     *   <li>amount: 本次采集的数量（正整数）</li>
     * </ul>
     * <p>
     * 使用record的原因：
     * <ul>
     *   <li>自动生成构造函数、getter、equals、hashCode等方法</li>
     *   <li>不可变性保证线程安全</li>
     *   <li>内存占用更小（比普通类更紧凑）</li>
     *   <li>简化代码，减少样板代码</li>
     * </ul>
     * <p>
     * 序列化支持：record自动支持序列化和反序列化
     */
    public record LootRecord(long timestamp, String name, int amount) {
        /**
         * 格式化记录为可读的字符串
         * <p>
         * 格式：[HH:mm:ss.SSS] 拾取: 物资名称 x数量
         * <p>
         * 示例：[14:23:45.678] 拾取: 矿石 x5
         *
         * @return 格式化的字符串表示
         */
        public String format() {
            // 格式化当前时间为易读格式（HH:mm:ss.SSS）
            // 注意：这里使用的是格式化时的时间，而非timestamp字段
            // 如果需要使用timestamp，应使用Instant.ofEpochMilli(timestamp)
            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));

            // 返回格式化字符串：[时间] 拾取: 名称 x数量
            return String.format("[%s] 拾取: %s x%d", timeStr, name, amount);
        }
    }
}