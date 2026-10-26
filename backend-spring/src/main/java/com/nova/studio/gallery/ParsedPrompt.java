package com.nova.studio.gallery;

import java.util.List;

/**
 * WIN-42 (T15, ADR-40) — 单个解析后的提示词条目（入库形状）。
 * 对应前端 {@code PromptWithKey}；{@code id} 即 {@code uniqueKey}（source-源内 id，幂等键）。
 *
 * @param id          uniqueKey（source-源内 id）
 * @param source      数据源标识
 * @param sourceUrl   来源 GitHub 链接
 * @param title       标题
 * @param content     提示词文本
 * @param images      图片 URL 列表（外链）
 * @param tags        标签列表
 * @param category    推断分类
 * @param contributor 贡献者
 * @param notes       备注
 * @param rawSnapshot 源原始数据快照（排障/重解析）
 */
public record ParsedPrompt(String id, String source, String sourceUrl, String title, String content,
                           List<String> images, List<String> tags, String category,
                           String contributor, String notes, String rawSnapshot) {
}
