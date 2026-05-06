package com.luoke.app.utils;

import com.luoke.app.model.ItemResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCR结果验证器和解析器
 * 将OCR识别的原始文本解析为结构化的物品结果对象
 */
public class OcrResultValidator {
    /**
     * 物品名称和数量的正则匹配模式
     * 格式：中文名称 + 分隔符(x/X/×/s) + 数字
     */
    private static final Pattern ITEM_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]+)[xX×*\\s]*(\\d+)");

    /**
     * 将原始OCR识别的文本行解析为结构化的物品结果对象
     * @param rawText OCR识别的原始文本行
     * @return 解析成功的ItemResult对象，失败返回null
     */
    public static ItemResult parse(String rawText) {
        if (rawText == null || rawText.isBlank()) return null;

        String cleanText = rawText.replace("莫", "草")
                .replace("困", "菌")
                .replace("查", "杏")
                .replace("艹", "草");

        Matcher matcher = ITEM_PATTERN.matcher(cleanText);
        if (matcher.find()) {
            String name = matcher.group(1);
            try {
                int count = Integer.parseInt(matcher.group(2));
                return new ItemResult(name, count);
            } catch (NumberFormatException e) {
                return new ItemResult(name, 1);
            }
        }

        String nameOnly = cleanText.replaceAll("[^\\u4e00-\\u9fa5]", "");
        if (nameOnly.length() >= 2) {
            return new ItemResult(nameOnly, 1);
        }

        return null;
    }
}
