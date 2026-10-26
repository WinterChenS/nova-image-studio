package com.nova.studio.gallery;

import java.util.List;
import java.util.Locale;

/**
 * WIN-42 (T15, ADR-40) — 提示广场分类推断（Java 移植自前端 {@code inferCategory}）。
 * 基于标题/内容/标签的中英文关键词推断默认分类；未命中返回「其他」。
 */
public final class GalleryCategory {

    public static final String CATEGORY_ALL = "全部";
    public static final String CATEGORY_POSTER = "海报";
    public static final String CATEGORY_CHARACTER = "角色";
    public static final String CATEGORY_ECOMMERCE = "电商";
    public static final String CATEGORY_UI = "UI";
    public static final String CATEGORY_STYLE = "风格转换";
    public static final String CATEGORY_GPT4O = "gpt4o";
    public static final String CATEGORY_GPT_IMAGE_2 = "gpt-image-2";
    public static final String CATEGORY_OTHER = "其他";

    /** 默认分类列表（前端 DEFAULT_CATEGORIES 不含「全部」）。 */
    public static final List<String> DEFAULT_CATEGORIES = List.of(
            CATEGORY_POSTER, CATEGORY_CHARACTER, CATEGORY_ECOMMERCE, CATEGORY_UI,
            CATEGORY_STYLE, CATEGORY_GPT_IMAGE_2, CATEGORY_GPT4O, CATEGORY_OTHER);

    private GalleryCategory() {
    }

    /** 分类推断（对应前端 inferCategory）。 */
    public static String infer(String title, String content, List<String> tags) {
        StringBuilder sb = new StringBuilder();
        if (title != null) {
            sb.append(title).append(' ');
        }
        if (content != null) {
            sb.append(content).append(' ');
        }
        if (tags != null) {
            for (String tag : tags) {
                sb.append(tag).append(' ');
            }
        }
        String text = sb.toString().toLowerCase(Locale.ROOT);
        if (text.contains("海报") || text.contains("poster")) {
            return CATEGORY_POSTER;
        }
        if (text.contains("角色") || text.contains("character") || text.contains(" oc")) {
            return CATEGORY_CHARACTER;
        }
        if (text.contains("电商") || text.contains("商品") || text.contains("product")) {
            return CATEGORY_ECOMMERCE;
        }
        if (text.contains("ui") || text.contains("界面") || text.contains("设计")) {
            return CATEGORY_UI;
        }
        if (text.contains("风格") || text.contains("转换") || text.contains("style")) {
            return CATEGORY_STYLE;
        }
        if (text.contains("gpt4o")) {
            return CATEGORY_GPT4O;
        }
        if (text.contains("gpt-image-2")) {
            return CATEGORY_GPT_IMAGE_2;
        }
        return CATEGORY_OTHER;
    }
}
