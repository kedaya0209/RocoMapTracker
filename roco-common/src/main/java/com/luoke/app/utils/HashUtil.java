package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文件哈希校验工具 — MD5 / SHA-256。
 */
@Slf4j
@ThreadSafe
public final class HashUtil {

    private static final int BUF_SIZE = 8192;

    private HashUtil() {
    }

    // ==================== SHA-256 ====================

    /**
     * 计算文件的 SHA-256 值。
     *
     * @return 十六进制小写字符串，失败返回空字符串
     */
    public static String computeFileSHA256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return computeSHA256(in);
        } catch (IOException e) {
            log.warn("计算文件 SHA256 失败：{}", file.getAbsolutePath(), e);
            return "";
        }
    }

    /**
     * 计算输入流的 SHA-256 值。
     */
    public static String computeSHA256(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[BUF_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 算法不可用", e);
        }
    }

    // ==================== MD5 ====================

    /**
     * 计算内置资源（classpath）的 MD5 值。
     *
     * @return 十六进制小写字符串，资源不存在或失败返回空字符串
     */
    public static String computeResourceMD5(String internalPath) {
        try (InputStream in = HashUtil.class.getResourceAsStream(internalPath)) {
            if (in == null) return "";
            return computeMD5(in);
        } catch (IOException e) {
            log.warn("计算内置资源 MD5 失败：{}", internalPath, e);
            return "";
        }
    }

    /**
     * 计算文件的 MD5 值。
     *
     * @return 十六进制小写字符串，失败返回空字符串
     */
    public static String computeFileMD5(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return computeMD5(in);
        } catch (IOException e) {
            log.warn("计算文件 MD5 失败：{}", file.getAbsolutePath(), e);
            return "";
        }
    }

    /**
     * 计算输入流的 MD5 值。
     */
    public static String computeMD5(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[BUF_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 算法不可用", e);
        }
    }
}
