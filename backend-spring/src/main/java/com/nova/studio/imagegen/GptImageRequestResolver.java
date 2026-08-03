package com.nova.studio.imagegen;

import java.util.Set;

/**
 * GPT image request size/advanced-params resolution — ports of the Node backend's
 * {@code getGptImageSize} / {@code normalizeCustomImageSize} /
 * {@code resolveGptImageRequestSize} / {@code normalizeGptImageAdvancedParams}
 * ({@code backend/server.js}). The frontend passes raw values and the backend
 * normalizes them unconditionally (开源版: 后端无条件透传/归一化).
 */
public final class GptImageRequestResolver {

    private GptImageRequestResolver() {
    }

    public static final Set<String> GPT_IMAGE_QUALITIES = Set.of("auto", "high", "medium", "low");
    public static final Set<String> GPT_IMAGE_STYLES = Set.of("auto", "vivid", "natural");
    public static final Set<String> GPT_IMAGE_BACKGROUNDS = Set.of("auto", "transparent", "opaque");

    public static final int CUSTOM_MULTIPLE = 16;
    public static final double MAX_ASPECT_RATIO = 3.0;
    public static final long MIN_PIXELS = 655360;
    public static final long MAX_PIXELS = 8294400;

    public record AdvancedParams(String quality, String style, String background) {
    }

    private static long roundToMultiple(long value, long multiple) {
        return Math.max(multiple, Math.round((double) value / multiple) * multiple);
    }

    /** Parses "WxH" (x, X or ×) into {width, height}; undefined otherwise. */
    public static long[] parseImageSize(String size) {
        if (size == null) {
            return null;
        }
        var m = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*[xX×]\\s*(\\d+)\\s*$").matcher(size.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            long width = Long.parseLong(m.group(1));
            long height = Long.parseLong(m.group(2));
            return new long[]{width, height};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isWithinLimits(long width, long height, long maxSide) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        long limit = maxSide > 0 ? maxSide : Long.MAX_VALUE;
        long longSide = Math.max(width, height);
        long shortSide = Math.min(width, height);
        long pixels = width * height;
        return longSide <= limit
                && width % CUSTOM_MULTIPLE == 0
                && height % CUSTOM_MULTIPLE == 0
                && (double) longSide / shortSide <= MAX_ASPECT_RATIO
                && pixels >= MIN_PIXELS
                && pixels <= MAX_PIXELS;
    }

    /** Node getGptImageSize: outputSize/aspectRatio → "WxH" for 1K/2K/4K presets. */
    public static String getGptImageSize(String outputSize, String aspectRatio) {
        if (outputSize == null || outputSize.equals("auto") || outputSize.equals("512")
                || aspectRatio == null || aspectRatio.equals("auto")) {
            return null;
        }
        var m = java.util.regex.Pattern.compile("^(\\d+):(\\d+)$").matcher(aspectRatio.trim());
        if (!m.matches()) {
            return null;
        }
        long ratioWidth = Long.parseLong(m.group(1));
        long ratioHeight = Long.parseLong(m.group(2));
        if (ratioWidth == 0 || ratioHeight == 0) {
            return null;
        }
        if (ratioWidth == ratioHeight) {
            long side = switch (outputSize) {
                case "1K" -> 1024;
                case "2K" -> 2048;
                default -> 3840;
            };
            return side + "x" + side;
        }
        if (outputSize.equals("1K")) {
            long shortSide = 1024;
            long width = ratioWidth > ratioHeight
                    ? roundToMultiple(Math.round(shortSide * (double) ratioWidth / ratioHeight), CUSTOM_MULTIPLE)
                    : shortSide;
            long height = ratioWidth > ratioHeight
                    ? shortSide
                    : roundToMultiple(Math.round(shortSide * (double) ratioHeight / ratioWidth), CUSTOM_MULTIPLE);
            return width + "x" + height;
        }
        if (!outputSize.equals("2K") && !outputSize.equals("4K")) {
            return null;
        }
        long longSide = outputSize.equals("2K") ? 2048 : 3840;
        long width = ratioWidth > ratioHeight
                ? longSide
                : roundToMultiple(Math.round(longSide * (double) ratioWidth / ratioHeight), CUSTOM_MULTIPLE);
        long height = ratioWidth > ratioHeight
                ? roundToMultiple(Math.round(longSide * (double) ratioHeight / ratioWidth), CUSTOM_MULTIPLE)
                : longSide;
        return width + "x" + height;
    }

    /** Node normalizeCustomImageSize: custom "WxH" normalized to multiple-of-16 within maxSide. */
    public static String normalizeCustomImageSize(String size, long maxSide) {
        long[] parsed = parseImageSize(size);
        if (parsed == null) {
            return null;
        }
        long limit = maxSide > 0 ? maxSide : Long.MAX_VALUE;
        long width = Math.min(roundToMultiple(parsed[0], CUSTOM_MULTIPLE), limit);
        long height = Math.min(roundToMultiple(parsed[1], CUSTOM_MULTIPLE), limit);
        if (!isWithinLimits(width, height, maxSide)) {
            return null;
        }
        return width + "x" + height;
    }

    /** Node resolveGptImageRequestSize: customSize wins, else preset resolution. */
    public static String resolveGptImageRequestSize(String customSize, String model, String outputSize, String aspectRatio) {
        String custom = normalizeCustomImageSize(customSize, 4096);
        if (custom != null) {
            return custom;
        }
        return getGptImageSize(outputSize, aspectRatio);
    }

    /** Node normalizeGptImageAdvancedParams: enum validation with 'auto' defaults. */
    public static AdvancedParams normalizeGptImageAdvancedParams(String quality, String style, String background) {
        String q = validateEnum(quality, GPT_IMAGE_QUALITIES, "quality");
        String s = validateEnum(style, GPT_IMAGE_STYLES, "style");
        String b = validateEnum(background, GPT_IMAGE_BACKGROUNDS, "background");
        return new AdvancedParams(q == null ? "auto" : q, s == null ? "auto" : s, b == null ? "auto" : b);
    }

    private static String validateEnum(String value, Set<String> valid, String fieldName) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (!valid.contains(value)) {
            throw new IllegalArgumentException(fieldName + " 参数无效");
        }
        return value;
    }
}
