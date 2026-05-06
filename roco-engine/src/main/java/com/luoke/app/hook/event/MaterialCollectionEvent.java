package com.luoke.app.hook.event;

import java.util.Map;

public record MaterialCollectionEvent(Map<String, Integer> summary) {
}