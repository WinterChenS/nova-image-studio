package com.nova.studio.gallery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WIN-42 (T15, ADR-40) — 提示广场 markdown 解析共用工具（Java 移植自前端
 * {@code prompt-gallery-data.ts} 的 splitBeforeHeading / firstMatch /
 * extractMarkdownImages / tagsFromHeading / tagsFromCategory / youMindTags）。
 */
final class GalleryMarkdown {

    private static final Pattern IMG_TAG = Pattern.compile("<img[^>]+src=\"([^\"]+)\"");
    private static final Pattern IMG_MD = Pattern.compile("!\\[[^\\]]*\\]\\(([^)]+)\\)");

    private GalleryMarkdown() {
    }

    /** 按前缀切块：以 prefix 开头的行作为新块起点（对应前端 splitBeforeHeading）。 */
    static List<String> splitBeforeHeading(String markdown, String prefix) {
        List<String> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith(prefix) && !current.isEmpty()) {
                blocks.add(String.join("\n", current));
                current = new ArrayList<>();
            }
            current.add(line);
        }
        if (!current.isEmpty()) {
            blocks.add(String.join("\n", current));
        }
        return blocks;
    }

    /** 首个捕获组（对应前端 firstMatch）。 */
    static String firstMatch(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        if (matcher.find() && matcher.groupCount() >= 1 && matcher.group(1) != null) {
            return matcher.group(1);
        }
        return "";
    }

    /** 提取 markdown 中的图片绝对 URL（去重，对应前端 extractMarkdownImages）。 */
    static List<String> extractMarkdownImages(PromptDataSource source, String block) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> images = new ArrayList<>();
        Matcher tagMatcher = IMG_TAG.matcher(block);
        while (tagMatcher.find()) {
            addImage(source, tagMatcher.group(1), seen, images);
        }
        Matcher mdMatcher = IMG_MD.matcher(block);
        while (mdMatcher.find()) {
            addImage(source, mdMatcher.group(1), seen, images);
        }
        return images;
    }

    private static void addImage(PromptDataSource source, String image, Set<String> seen, List<String> images) {
        if (image == null) {
            return;
        }
        String absolute = source.absoluteImage(image);
        if (!absolute.isBlank() && !seen.contains(absolute)) {
            seen.add(absolute);
            images.add(absolute);
        }
    }

    /** 标题标签（保留字母/数字/斜杠/与/顿号/和，对应前端 tagsFromHeading）。 */
    static List<String> tagsFromHeading(String heading) {
        if (heading == null || heading.isBlank()) {
            return List.of();
        }
        String cleaned = heading.replaceAll("[^\\p{L}\\p{N}/&、与 ]", "");
        List<String> tags = new ArrayList<>();
        for (String part : cleaned.split("\\s*(/|&|、|与)\\s*")) {
            String tag = part.trim().toLowerCase(Locale.ROOT);
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return tags;
    }

    /** 分类字符串拆标签（对应前端 tagsFromCategory，含 "Cases" 后缀剥离）。 */
    static List<String> tagsFromCategory(String category) {
        if (category == null || category.isBlank()) {
            return List.of();
        }
        String cleaned = category.replaceAll("\\s+Cases$", "");
        List<String> tags = new ArrayList<>();
        for (String part : cleaned.split("\\s*(&|and)\\s*")) {
            String tag = part.trim();
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return tags;
    }

    /** youmind 标签（modelTag + 标题前缀，对应前端 youMindTags）。 */
    static List<String> youMindTags(String title, String modelTag) {
        List<String> tags = new ArrayList<>();
        if (modelTag != null && !modelTag.isBlank()) {
            tags.add(modelTag);
        }
        String[] parts = title == null ? new String[0] : title.split(" - ", 2);
        if (parts.length > 1) {
            tags.addAll(tagsFromHeading(parts[0]));
        }
        return tags;
    }
}
