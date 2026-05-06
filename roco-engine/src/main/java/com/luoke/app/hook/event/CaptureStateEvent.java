package com.luoke.app.hook.event;

public record CaptureStateEvent(int id, boolean connected, String windowTitle) {
}