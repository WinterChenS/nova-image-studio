package com.nova.studio.gallery;

/**
 * WIN-42 (T15, ADR-40) — 提示广场数据源配置（Java 移植自前端
 * {@code prompt-gallery-data.ts} 的 PROMPT_DATA_SOURCES）。类型驱动
 * {@link PromptParserRegistry} 选择对应解析器。
 *
 * @param name      数据源标识（如 "nanobanana"）
 * @param url       远程源 URL（经 proxy.ccode.vip 前缀，ADR-40 依赖）
 * @param sourceUrl GitHub 来源链接（合规展示）
 * @param type      解析器类型（nanobanana|gpt-image-2|markdown-awesome|markdown-gpt4o|markdown-youmind|davidwu-json）
 * @param baseUrl   相对资源（case 文件/图片）拼接基址
 * @param caseFiles gpt-image-2 附加 case 文件列表
 * @param modelTag  youmind 系列模型标签
 */
public record PromptDataSource(String name, String url, String sourceUrl, String type,
                               String baseUrl, java.util.List<String> caseFiles, String modelTag) {

    /** 相对资源绝对化（对应前端 absoluteImage）。 */
    public String absoluteImage(String image) {
        if (image == null || image.isBlank()) {
            return "";
        }
        if (image.startsWith("http://") || image.startsWith("https://")) {
            return image;
        }
        String base = baseUrl == null ? "" : baseUrl;
        return base + "/" + image.replaceAll("^\\.", "").replaceAll("^/", "");
    }
}
