package io.github.kedaya0209.roco.app.socket;

import io.github.kedaya0209.roco.app.context.MaterialCollectionContext;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 外部桥接处理器 — 事件路由器。
 * <p>
 * 接收 rmt_bridge.py 推送的各种游戏事件并按 serviceId 分发：
 * <ul>
 *   <li>MSG_STOP_MATCHING (212) / MSG_START_MATCHING (213) → SIFT 匹配启停</li>
 *   <li>MSG_AREA_CHANGE (214) → 区域变更（body 为 UTF-8 区域名称）</li>
 *   <li>MSG_ITEM_PICKUP (215) → 物资拾取（body 为 UTF-8 物品名称）</li>
 * </ul>
 * <p>
 * 数据已在 Python 侧解析完成，Java 侧只做展示不做业务解析。
 */
@ThreadSafe
@Slf4j
public class ExternalBridgeHandler implements HandlerSubscriber.MessageHandler {

    private final Runnable onStopEngine;
    private final Runnable onStartEngine;
    private final Runnable onAreaChange;
    private final ItemPickupHandler onItemPickup;

    @FunctionalInterface
    public interface ItemPickupHandler {
        void onPickup(String itemName, int pickupNum, int backpackTotal);
    }

    public ExternalBridgeHandler(Runnable onStopEngine, Runnable onStartEngine) {
        this(onStopEngine, onStartEngine, null, null);
    }

    public ExternalBridgeHandler(Runnable onStopEngine, Runnable onStartEngine,
                                  Runnable onAreaChange,
                                  ItemPickupHandler onItemPickup) {
        this.onStopEngine = onStopEngine;
        this.onStartEngine = onStartEngine;
        this.onAreaChange = onAreaChange;
        this.onItemPickup = onItemPickup;
    }

    @Override
    public void onMessage(int serviceId, byte[] body, SocketSession sender) {
        if (serviceId == ExternalBridgeProtocol.MSG_STOP_MATCHING) {
            log.info("外部桥接 → 停止匹配");
            onStopEngine.run();
        } else if (serviceId == ExternalBridgeProtocol.MSG_START_MATCHING) {
            log.info("外部桥接 → 开始匹配");
            onStartEngine.run();
        } else if (serviceId == ExternalBridgeProtocol.MSG_AREA_CHANGE) {
            handleAreaChange(body);
        } else if (serviceId == ExternalBridgeProtocol.MSG_ITEM_PICKUP) {
            handleItemPickup(body);
        }
    }

    /**
     * 解码 body：优先 UTF-8 字符串，4 字节时回退到 uint32（兼容旧桥接）。
     */
    private String decodeBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        if (body.length == 4) {
            // 回退：可能是旧版 uint32（config 未加载时的 fallback）
            int rawId = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).getInt();
            return "#" + rawId;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private void handleAreaChange(byte[] body) {
        String areaName = decodeBody(body);
        if (areaName.isEmpty()) {
            log.warn("MSG_AREA_CHANGE body 为空");
            return;
        }
        log.info("外部桥接 → 区域变更: {}", areaName);
        if (onAreaChange != null) {
            onAreaChange.run();
        }
    }

    private void handleItemPickup(byte[] body) {
        String text = decodeBody(body);
        if (text.isEmpty()) {
            log.warn("MSG_ITEM_PICKUP body 为空");
            return;
        }
        // 解析: name|pickupNum|backpackTotal
        String[] parts = text.split("\\|", 3);
        String itemName = parts[0];
        int pickupNum = parts.length > 1 ? parseIntSafe(parts[1], 1) : 1;
        int backpackTotal = parts.length > 2 ? parseIntSafe(parts[2], 0) : 0;

        log.info("外部桥接 → 物资拾取: {} +{} 背包:{}", itemName, pickupNum, backpackTotal);
        if (onItemPickup != null) {
            onItemPickup.onPickup(itemName, pickupNum, backpackTotal);
        } else {
            MaterialCollectionContext.getInstance().updateFromNetwork(itemName, pickupNum, backpackTotal);
        }
    }

    private static int parseIntSafe(String s, int defaultValue) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
