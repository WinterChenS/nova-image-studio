package com.nova.studio.infra;

import java.util.regex.Pattern;

/**
 * Port of the Node backend's {@code normalizeError} ({@code backend/server.js}):
 * maps network/timeout error messages to stable Chinese user-facing messages
 * and truncates anything longer than 200 chars so internal details (paths,
 * stack traces) never leak to clients.
 */
public final class NormalizedError {

    private static final Pattern NETWORK_PATTERN = Pattern.compile(
            "(?i)failed to fetch|fetch failed|networkerror|network request failed|load failed|"
                    + "network connection was lost|econnreset|socket hang up|terminated");
    private static final Pattern TIMEOUT_PATTERN = Pattern.compile("(?i)abort|timeout|timed out");

    private NormalizedError() {
    }

    public static String normalize(Throwable error, long requestTimeoutMs) {
        return normalize(error == null ? null : error.getMessage(), requestTimeoutMs);
    }

    public static String normalize(String message, long requestTimeoutMs) {
        String text = String.valueOf(message);
        if (NETWORK_PATTERN.matcher(text).find()) {
            return "网络连接失败。请检查服务器网络连接或稍后重试。";
        }
        if (TIMEOUT_PATTERN.matcher(text).find()) {
            return String.format("请求超时（%d秒）。高分辨率图片生成需要更长时间，请稍后重试。", requestTimeoutMs / 1000);
        }
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }
}
