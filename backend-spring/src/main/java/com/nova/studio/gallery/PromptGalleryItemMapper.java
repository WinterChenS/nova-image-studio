package com.nova.studio.gallery;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * WIN-42 (T15, ADR-40) — MyBatis-Plus mapper for {@code prompt_gallery_items}.
 * {@code id} = source-uniqueKey（幂等 upsert）。
 */
@Mapper
public interface PromptGalleryItemMapper extends BaseMapper<PromptGalleryItemEntity> {

    /**
     * 幂等 upsert（id = source-uniqueKey）：存在则更新内容与同步时间，不存在则插入。
     * images/tags/raw_snapshot 为 jsonb 列，以 unknown 类型绑定（与 SettingsMapper
     * 的 {@code ::jsonb} 语义一致）。
     */
    @Insert("""
            INSERT INTO prompt_gallery_items
                (id, source, source_url, title, content, images, tags, category,
                 contributor, notes, raw_snapshot, synced_at)
            VALUES
                (#{id}, #{source}, #{sourceUrl}, #{title}, #{content},
                 #{images}::jsonb, #{tags}::jsonb, #{category},
                 #{contributor}, #{notes}, #{rawSnapshot}::jsonb, #{syncedAt})
            ON CONFLICT (id) DO UPDATE SET
                source = EXCLUDED.source,
                source_url = EXCLUDED.source_url,
                title = EXCLUDED.title,
                content = EXCLUDED.content,
                images = EXCLUDED.images,
                tags = EXCLUDED.tags,
                category = EXCLUDED.category,
                contributor = EXCLUDED.contributor,
                notes = EXCLUDED.notes,
                raw_snapshot = EXCLUDED.raw_snapshot,
                synced_at = EXCLUDED.synced_at
            """)
    void upsert(@Param("id") String id, @Param("source") String source,
                @Param("sourceUrl") String sourceUrl, @Param("title") String title,
                @Param("content") String content, @Param("images") String images,
                @Param("tags") String tags, @Param("category") String category,
                @Param("contributor") String contributor, @Param("notes") String notes,
                @Param("rawSnapshot") String rawSnapshot, @Param("syncedAt") java.time.Instant syncedAt);
}
