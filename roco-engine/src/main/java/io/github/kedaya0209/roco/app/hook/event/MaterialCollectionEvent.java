package io.github.kedaya0209.roco.app.hook.event;

import net.jcip.annotations.ThreadSafe;
import java.util.Map;

@ThreadSafe
public record MaterialCollectionEvent(Map<String, Integer> summary, Map<String, Integer> backpackTotals) {
    public MaterialCollectionEvent(Map<String, Integer> summary) {
        this(summary, Map.of());
    }
}