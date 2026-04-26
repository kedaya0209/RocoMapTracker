package com.luoke.app.utils;

import com.luoke.app.model.ItemResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCR结果验证器和解析器
 *
 * <p>该类负责将OCR识别的原始文本解析为结构化的物品结果对象。
 * 主要功能包括文本纠错、正则表达式匹配和数量提取。</p>
 *
 * <p><b>设计特点：</b></p>
 * <ul>
 *   <li>使用预编译的正则表达式提升性能，避免重复编译开销</li>
 *   <li>支持多种常见OCR误识别字符的自动纠错</li>
 *   <li>提供灵活的回退策略，确保数据解析的健壮性</li>
 * </ul>
 *
 * <p><b>Native Image优化：</b></p>
 * <ul>
 *   <li>正则表达式编译在静态初始化阶段完成，支持Native Image AOT编译</li>
 *   <li>无反射调用，完全兼容GraalVM Native Image</li>
 * </ul>
 *
 * @author RocoMapTracker Team
 * @version 1.0
 */
public class OcrResultValidator {
    /**
     * 物品名称和数量的正则匹配模式
     *
     * <p>正则表达式说明：</p>
     * <ul>
     *   <li>第1组 ([\\u4e00-\\u9fa5]+)：匹配中文名称（Unicode中文范围）</li>
     *   <li>分隔符 [xX×*\\s]*：匹配数量连接符（支持x/X/×/*和空格）</li>
     *   <li>第2组 (\\d+)：匹配数字数量</li>
     * </ul>
     *
     * <p><b>性能优化：</b></p>
     * <ul>
     *   <li>使用static final修饰，在类加载时编译一次，后续直接使用</li>
     *   <li>避免在每次解析时重新编译正则表达式，显著提升性能</li>
     *   <li>在Native Image环境中，此编译发生在镜像构建阶段</li>
     * </ul>
     *
     * <p><b>使用示例：</b></p>
     * <pre>
     * “草x10”  → 名称: “草”, 数量: 10
     * “菌×5”   → 名称: “菌”, 数量: 5
     * “草 * 3” → 名称: “草”, 数量: 3
     * </pre>
     */
    private static final Pattern ITEM_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]+)[xX×*\\s]*(\\d+)");

    /**
     * 将原始OCR识别的文本行解析为结构化的物品结果对象
     *
     * <p><b>解析流程：</b></p>
     * <ol>
     *   <li>输入验证：检查文本是否为空或空白</li>
     *   <li>文本纠错：修复常见的OCR误识别字符</li>
     *   <li>正则匹配：尝试提取名称和数量</li>
     *   <li>回退策略：如果正则匹配失败，提取纯中文并设置数量为1</li>
     * </ol>
     *
     * <p><b>纠错策略：</b></p>
     * <ul>
     *   <li>”莫” → “草”：OCR常将”草”误认为”莫”</li>
     *   <li>”困” → “菌”：OCR常将”菌”误认为”困”</li>
     *   <li>”艹” → “草”：OCR常将”草”误认为”艹”</li>
     * </ul>
     *
     * <p><b>内存生命周期：</b></p>
     * <ul>
     *   <li>方法内部创建的String对象由JVM自动管理</li>
     *   <li>返回的ItemResult对象交由调用方管理其生命周期</li>
     *   <li>无Native资源需要手动释放</li>
     * </ul>
     *
     * @param rawText OCR识别的原始文本行，可能包含物品名称和数量
     * @return 解析成功的ItemResult对象，包含物品名称和数量；
     *         如果文本为空、空白或无法解析，返回null
     *
     * @see ItemResult
     */
    public static ItemResult parse(String rawText) {
        // 输入验证：防御性编程，避免空指针异常
        if (rawText == null || rawText.isBlank()) return null;

        // 步骤1：核心纠错逻辑
        // 针对OCR识别中常见的字符误识别问题进行修正
        // 这些替换基于实际测试中总结的误识别规律
        String cleanText = rawText.replace("莫", "草")
                .replace("困", "菌")
                .replace("查", "杏")
                .replace("艹", "草");

        // 步骤2：使用预编译的正则表达式解析数量
        // 相比每次编译正则，使用预编译的Pattern对象性能提升显著
        Matcher matcher = ITEM_PATTERN.matcher(cleanText);
        if (matcher.find()) {
            // 提取匹配到的名称（第1组）和数量（第2组）
            String name = matcher.group(1);
            try {
                // 尝试将数量字符串转换为整数
                int count = Integer.parseInt(matcher.group(2));
                return new ItemResult(name, count);
            } catch (NumberFormatException e) {
                // 如果数字解析失败（理论上不应该发生，因为是\\d+匹配），使用默认值1
                // 这是防御性编程，确保系统健壮性
                return new ItemResult(name, 1);
            }
        }

        // 步骤3：回退方案 - 处理没有数量标记的情况
        // 提取纯中文字符，长度至少为2才认为是有效名称
        // 这种情况可能是OCR没有识别到数量分隔符
        String nameOnly = cleanText.replaceAll("[^\\u4e00-\\u9fa5]", "");
        if (nameOnly.length() >= 2) {
            // 数量默认为1，表示单个物品
            return new ItemResult(nameOnly, 1);
        }

        // 所有解析策略都失败，返回null表示无效的OCR结果
        return null;
    }
}