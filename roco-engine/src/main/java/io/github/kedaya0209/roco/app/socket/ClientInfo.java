package io.github.kedaya0209.roco.app.socket;

import net.jcip.annotations.ThreadSafe;

import java.util.Set;

/**
 * 客户端注册信息 — 在 HELLO 握手时由客户端自声明
 *
 * @param clientId    客户端标识，如 "capture", "sift", "pcap"
 * @param provides    我提供的服务（我是生产者）
 * @param subscribes  我订阅的服务（我是消费者）
 */
@ThreadSafe
public record ClientInfo(String clientId, Set<Integer> provides, Set<Integer> subscribes) {
}
