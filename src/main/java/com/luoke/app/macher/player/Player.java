package com.luoke.app.macher.player;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 玩家实体类
 *
 * <p>表示游戏地图中的玩家位置和朝向信息。</p>
 * <p>使用Lombok注解简化代码，支持Builder模式创建对象。</p>
 *
 * <h3>属性说明：</h3>
 * <ul>
 *   <li>found：是否成功检测到玩家</li>
 *   <li>angle：玩家朝向角度（0-360度）</li>
 *   <li>pos：玩家在大地图上的像素坐标</li>
 *   <li>viewAngle：可视区域朝向（预留字段，当前未使用）</li>
 * </ul>
 *
 * <h3>角度说明：</h3>
 * <ul>
 *   <li>0度：向右（东方）</li>
 *   <li>90度：向下（南方）</li>
 *   <li>180度：向左（西方）</li>
 *   <li>270度：向上（北方）</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 使用Builder模式创建
 * Player player = Player.builder()
 *     .found(true)
 *     .angle(45.5)
 *     .pos(new Point(100, 200))
 *     .build();
 *
 * // 使用默认构造函数创建
 * Player player = new Player();
 * player.setFound(true);
 * player.setAngle(45.5);
 * player.setPos(new Point(100, 200));
 *
 * // 判断是否检测到玩家
 * if (player.isFound()) {
 *     double angle = player.getAngle();
 *     Point pos = player.getPos();
 *     System.out.println("玩家位置: (" + pos.x() + ", " + pos.y() + ")");
 *     System.out.println("玩家朝向: " + angle + "度");
 * }
 * }</pre>
 *
 * <h3>Native资源管理：</h3>
 * <ul>
 *   <li>pos字段包含Native Point对象，需要在使用后释放</li>
 *   <li>建议使用try-with-resources管理pos对象</li>
 * </ul>
 *
 * @author 可达鸭
 * @version 1.0
 */
@Data  // Lombok注解：自动生成getter、setter、toString、equals、hashCode方法
@Builder  // Lombok注解：支持Builder模式创建对象
@NoArgsConstructor  // Lombok注解：生成无参构造函数
@AllArgsConstructor  // Lombok注解：生成全参构造函数
public class Player {

    /**
     * 是否成功检测到玩家
     * <p>true：玩家被成功定位</p>
     * <p>false：未检测到玩家或检测失败</p>
     */
    private boolean found;

    /**
     * 玩家朝向角度
     * <p>范围：0-360度</p>
     * <p>0度表示向右，90度表示向下，180度表示向左，270度表示向上</p>
     */
    private double angle;
}
