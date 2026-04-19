package com.luoke.macher.player;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.bytedeco.opencv.opencv_core.Point;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Player {
    // 是否成功锁定玩家
    private boolean found;
    // 玩家朝向 (0-360)
    private double angle;
    // 玩家在原始大地图上的像素坐标
    private Point pos;
    // 可视区域朝向 (预留)
    private double viewAngle;
}