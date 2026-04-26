package com.luoke.app.event;

import lombok.Builder;

/**
 * 玩家位置事件
 * <p>
 * 功能说明：
 * <ul>
 *   <li>封装玩家在游戏世界或屏幕上的位置坐标</li>
   *   <li>作为事件系统的数据载体，传递位置更新信息</li>
   *   <li>使用Java record实现轻量级不可变事件对象</li>
 * </ul>
 * <p>
 * 设计考虑：
 * <ul>
 *   <li>不可变性：record自动生成final字段和只读访问器</li>
 *   <li>轻量级：仅包含两个double字段，内存占用最小</li>
 *   <li>线程安全：不可变对象天然线程安全，支持跨线程传递</li>
 *   <li>Builder支持：使用Lombok @Builder，提供灵活的构建方式</li>
 * </ul>
 * <p>
 * 使用场景：
 * <ul>
 *   <li>ResourceGrayHook接收此事件，计算玩家与资源的距离</li>
 *   <li>其他Hook可订阅此事件，实现位置相关的业务逻辑</li>
 *   <li>事件总线通过此类传递玩家位置更新</li>
 * </ul>
 * <p>
 * 坐标系统：
 * <ul>
 *   <li>通常使用屏幕坐标（像素），原点在左上角</li>
 *   <li>x轴向右递增，y轴向下递增</li>
 *   <li>具体坐标系统需根据实际使用场景确认</li>
 * </ul>
 * <p>
 * 注意事项：
 * <ul>
 *   <li>坐标可能超出屏幕范围，需由使用方校验</li>
 *   <li>坐标精度为double，支持浮点运算</li>
 *   <li>序列化：record自动实现Serializable，支持网络传输</li>
 * </ul>
 *
 * @param x 玩家位置的x坐标，通常为屏幕横向坐标（单位：像素）
 * @param y 玩家位置的y坐标，通常为屏幕纵向坐标（单位：像素）
 */
@Builder
public record PlayerPositionEvent(double x, double y) {
}
