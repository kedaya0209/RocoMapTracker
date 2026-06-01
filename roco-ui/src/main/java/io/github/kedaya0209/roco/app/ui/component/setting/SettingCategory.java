package io.github.kedaya0209.roco.app.ui.component.setting;

import net.jcip.annotations.ThreadSafe;
import java.util.List;

/**
 * 设置分类 — 左侧列表中的一个分类及其中所有字段定义。
 *
 * @param name    分类名称
 * @param iconSvg 图标资源路径
 * @param fields  字段列表
 */
@ThreadSafe
public record SettingCategory(String name, String iconSvg, List<SettingDef> fields) {
}
