package com.nova.studio.gallery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * WIN-42 (T15, ADR-40) — {@code prompt_gallery_items} table access：幂等 upsert
 * + 读库搜索（source/category 精确过滤 + q ILIKE 标题/内容 + tag 标签包含，T16 服务端化）。
 */
@Repository
public class PromptGalleryItemRepository {

    /** 分页结果（读库 API 主入口，AC-9）。 */
    public record GalleryPage(List<PromptGalleryItemEntity> items, long total, int page, int pageSize) {
    }

    private final PromptGalleryItemMapper mapper;

    public PromptGalleryItemRepository(PromptGalleryItemMapper mapper) {
        this.mapper = mapper;
    }

    /** 幂等 upsert（id = source-uniqueKey，ADR-40）。 */
    public void upsert(String id, String source, String sourceUrl, String title, String content,
                       String images, String tags, String category, String contributor,
                       String notes, String rawSnapshot, Instant syncedAt) {
        mapper.upsert(id, source, sourceUrl, title, content,
                images == null ? "[]" : images,
                tags == null ? "[]" : tags,
                category, contributor, notes,
                rawSnapshot, syncedAt);
    }

    public Optional<PromptGalleryItemEntity> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    public long countAll() {
        Long count = mapper.selectCount(new LambdaQueryWrapper<>());
        return count == null ? 0 : count;
    }

    /**
     * 读库搜索（T16 服务端化）：source/category 精确；q 对 title/content/contributor
     * 做 ILIKE 模糊；tag 匹配 tags 数组元素（ILIKE 数组文本）。按 synced_at DESC 分页。
     */
    public GalleryPage search(String source, String category, String q, String tag,
                              int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize <= 0 ? 20 : pageSize, 1), 100);
        LambdaQueryWrapper<PromptGalleryItemEntity> wrapper = new LambdaQueryWrapper<>();
        if (source != null && !source.isBlank()) {
            wrapper.eq(PromptGalleryItemEntity::getSource, source);
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(PromptGalleryItemEntity::getCategory, category);
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + escapeLike(q.trim()) + "%";
            wrapper.and(w -> w.apply("title ILIKE {0}", like)
                    .or().apply("content ILIKE {0}", like)
                    .or().apply("contributor ILIKE {0}", like));
        }
        if (tag != null && !tag.isBlank()) {
            wrapper.apply("tags::text ILIKE {0}", "%" + escapeLike(tag.trim()) + "%");
        }
        Long total = mapper.selectCount(wrapper);
        List<PromptGalleryItemEntity> rows = mapper.selectList(
                wrapper.orderByDesc(PromptGalleryItemEntity::getSyncedAt)
                        .last("LIMIT " + safeSize + " OFFSET " + (safePage - 1) * safeSize));
        return new GalleryPage(rows, total == null ? 0 : total, safePage, safeSize);
    }

    /** 全部分类（去重，供前端分类栏）。 */
    public List<String> distinctCategories() {
        List<PromptGalleryItemEntity> rows = mapper.selectList(new LambdaQueryWrapper<PromptGalleryItemEntity>()
                .select(PromptGalleryItemEntity::getCategory));
        Set<String> set = new LinkedHashSet<>();
        for (PromptGalleryItemEntity row : rows) {
            if (row.getCategory() != null && !row.getCategory().isBlank()) {
                set.add(row.getCategory());
            }
        }
        return new ArrayList<>(set);
    }

    /** 全部来源（去重，供前端来源筛选）。 */
    public List<String> distinctSources() {
        List<PromptGalleryItemEntity> rows = mapper.selectList(new LambdaQueryWrapper<PromptGalleryItemEntity>()
                .select(PromptGalleryItemEntity::getSource));
        Set<String> set = new LinkedHashSet<>();
        for (PromptGalleryItemEntity row : rows) {
            if (row.getSource() != null && !row.getSource().isBlank()) {
                set.add(row.getSource());
            }
        }
        return new ArrayList<>(set);
    }

    /** ILIKE 通配符转义（%/_ → 字面量），防搜索注入。 */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
