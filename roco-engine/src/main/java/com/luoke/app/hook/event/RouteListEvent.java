package com.luoke.app.hook.event;

import com.luoke.app.map.model.RoutePath;
import net.jcip.annotations.ThreadSafe;

import java.util.List;

/**
 * 路线列表变更事件
 *
 * @param routes 当前路线列表的不可变快照（防御性拷贝）
 */
@ThreadSafe
public record RouteListEvent(List<RoutePath> routes) {
}
