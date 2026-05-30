package io.github.kedaya0209.roco.app.socket;

import net.jcip.annotations.ThreadSafe;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务注册中心 — 管理 provider/subscriber 映射
 * <p>
 * 核心数据结构:
 * - subscribers: serviceId → 订阅者集合（不区分内外）
 * - providers:   serviceId → 提供者集合
 * - clients:     clientId → 注册信息
 */
@ThreadSafe
@Slf4j
public class ServiceRegistry {

    /** serviceId → 订阅者集合 */
    private final ConcurrentHashMap<Integer, Set<MessageSubscriber>> subscribers = new ConcurrentHashMap<>();

    /** serviceId → 提供者集合 */
    private final ConcurrentHashMap<Integer, Set<MessageSubscriber>> providers = new ConcurrentHashMap<>();

    /** clientId → 注册信息 */
    private final ConcurrentHashMap<String, ClientInfo> clients = new ConcurrentHashMap<>();

    /**
     * 注册客户端：记录客户端信息，将其订阅和提供的服务写入映射表
     */
    public void register(ClientInfo info, MessageSubscriber subscriber) {
        clients.put(info.clientId(), info);

        for (int serviceId : info.provides()) {
            providers.computeIfAbsent(serviceId, k -> ConcurrentHashMap.newKeySet()).add(subscriber);
        }
        for (int serviceId : info.subscribes()) {
            subscribers.computeIfAbsent(serviceId, k -> ConcurrentHashMap.newKeySet()).add(subscriber);
        }

        log.info("注册客户端: clientId={} provides={} subscribes={}",
                info.clientId(), info.provides(), info.subscribes());
    }

    /**
     * 注销客户端：从所有映射表中移除
     */
    public void unregister(MessageSubscriber subscriber) {
        String clientId = subscriber.clientId();
        ClientInfo info = clients.remove(clientId);

        if (info != null) {
            for (int serviceId : info.provides()) {
                Set<MessageSubscriber> set = providers.get(serviceId);
                if (set != null) {
                    set.remove(subscriber);
                    if (set.isEmpty()) providers.remove(serviceId);
                }
            }
            for (int serviceId : info.subscribes()) {
                Set<MessageSubscriber> set = subscribers.get(serviceId);
                if (set != null) {
                    set.remove(subscriber);
                    if (set.isEmpty()) subscribers.remove(serviceId);
                }
            }
        }

        // 兜底：遍历所有映射表移除（防止 info 丢失导致残留）
        subscribers.values().forEach(set -> set.remove(subscriber));
        providers.values().forEach(set -> set.remove(subscriber));

        log.info("注销客户端: clientId={}", clientId);
    }

    /**
     * 运行时动态订阅
     */
    public void subscribe(int serviceId, MessageSubscriber subscriber) {
        subscribers.computeIfAbsent(serviceId, k -> ConcurrentHashMap.newKeySet()).add(subscriber);
        // 同步更新 clients 中的 subscribes
        ClientInfo info = clients.get(subscriber.clientId());
        if (info != null) {
            Set<Integer> newSubscribes = ConcurrentHashMap.newKeySet();
            newSubscribes.addAll(info.subscribes());
            newSubscribes.add(serviceId);
            clients.put(subscriber.clientId(), new ClientInfo(info.clientId(), info.provides(), newSubscribes));
        }
        log.debug("动态订阅: clientId={} serviceId={}", subscriber.clientId(), serviceId);
    }

    /**
     * 运行时动态取消订阅
     */
    public void unsubscribe(int serviceId, MessageSubscriber subscriber) {
        Set<MessageSubscriber> set = subscribers.get(serviceId);
        if (set != null) {
            set.remove(subscriber);
            if (set.isEmpty()) subscribers.remove(serviceId);
        }
        log.debug("取消订阅: clientId={} serviceId={}", subscriber.clientId(), serviceId);
    }

    /**
     * 获取指定 serviceId 的所有订阅者
     */
    public Set<MessageSubscriber> getSubscribers(int serviceId) {
        return subscribers.getOrDefault(serviceId, Set.of());
    }

    /**
     * 获取指定 serviceId 的所有提供者
     */
    public Set<MessageSubscriber> getProviders(int serviceId) {
        return providers.getOrDefault(serviceId, Set.of());
    }

    /**
     * 获取所有已注册的客户端信息
     */
    public Map<String, ClientInfo> getAllClients() {
        return Map.copyOf(clients);
    }

    /**
     * 获取指定客户端的注册信息
     */
    public ClientInfo getClient(String clientId) {
        return clients.get(clientId);
    }

    /**
     * 清空所有注册数据
     */
    public void clear() {
        subscribers.clear();
        providers.clear();
        clients.clear();
    }
}
