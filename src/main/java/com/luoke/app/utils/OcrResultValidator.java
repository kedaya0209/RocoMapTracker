package com.luoke.app.utils;

import com.luoke.app.model.ItemResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OcrResultValidator {
    // 匹配：中文名 + 数量连接符(xX×*等) + 数字
    private static final Pattern ITEM_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]+)[xX×*\\s]*(\\d+)");

    /**
     * 将原始 OCR 行文本解析为结构化对象
     */
    public static ItemResult parse(String rawText) {
        if (rawText == null || rawText.isBlank()) return null;

        // 1. 核心纠错：针对你反馈的“草/莫”误认
        String cleanText = rawText.replace("莫", "草")
                .replace("困", "菌")
                .replace("艹", "草");

        // 2. 正则解析数量
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

        // 3. 回退方案：如果没有数字，提取纯中文，数量默认为 1
        String nameOnly = cleanText.replaceAll("[^\\u4e00-\\u9fa5]", "");
        if (nameOnly.length() >= 2) {
            return new ItemResult(nameOnly, 1);
        }

        return null;
    }
}