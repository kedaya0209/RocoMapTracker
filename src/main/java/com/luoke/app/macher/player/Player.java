package com.luoke.app.macher.player;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 玩家实体类
 * 表示游戏地图中的玩家位置和朝向信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Player {

    /**
     * 是否成功检测到玩家
     */
    private boolean found;

    /**
     * 玩家朝向角度（0-360度）
     * 0度向右，90度向下，180度向左，270度向上
     */
    private double angle;
}
