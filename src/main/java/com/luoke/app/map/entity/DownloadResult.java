package com.luoke.app.map.entity;

import lombok.Data;

@Data
public class DownloadResult {
    private byte[] data;
    private boolean success;
    private boolean notFound;

    public static DownloadResult success(byte[] data) {
        DownloadResult r = new DownloadResult();
        r.data = data;
        r.success = true;
        return r;
    }

    public static DownloadResult notFound() {
        DownloadResult r = new DownloadResult();
        r.notFound = true;
        return r;
    }

    public static DownloadResult failed() {
        return new DownloadResult();
    }
}