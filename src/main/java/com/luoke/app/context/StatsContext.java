package com.luoke.app.context;

import lombok.Getter;

@Getter
public final class StatsContext {

    private static final StatsContext INSTANCE = new StatsContext();
    private long lastMapDetectMs;
    private long lastMatchMs;
    private long lastDirectionMs;
    private int frequency = 0;
    private int frameCounter;
    private long lastSecondTime = System.currentTimeMillis();

    public static StatsContext getInstance() {
        return INSTANCE;
    }

    public void recordMapDetect(long ms) {
        lastMapDetectMs = ms;
    }

    public void recordMatch(long ms) {
        lastMatchMs = ms;
    }

    public void recordDirection(long ms) {
        lastDirectionMs = ms;
    }

    public void onFrameProcessed() {
        frameCounter++;
        long now = System.currentTimeMillis();
        if (now - lastSecondTime >= 1000) {
            frequency = frameCounter;
            frameCounter = 0;
            lastSecondTime = now;
        }
    }
}