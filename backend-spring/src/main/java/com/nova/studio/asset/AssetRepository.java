package com.nova.studio.asset;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WIN-22 — {@code assets} table access (server-side asset metadata). Public
 * row shape is {@link AssetRow}; entities are converted at the boundary.
 * Every query filters by {@code user_id} (N-1 isolation); {@code projectId}
 * NULL means the 「未分类」 virtual bucket (G-4).
 */
@Repository
public class AssetRepository {

    /** Public row shape (service/test contract; storageKey excluded from API JSON). */
    public record AssetRow(String id, String userId, String projectId, String kind, String name,
                           String mimeType, Long sizeBytes, Integer width, Integer height,
                           String tags, String note, String sourceKind, String sourceLabel,
                           String sourceRef, String prompt, String storageKey, String hash,
                           Instant createdAt, Instant updatedAt, Instant lastUsedAt) {
    }

    /** A page of rows + total count. */
    public record AssetPage(List<AssetRow> items, long total) {
    }

    private final AssetMapper mapper;

    public AssetRepository(AssetMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<AssetRow> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(AssetRepository::toRow);
    }

    /** Finds by id restricted to an owner (used for 404-on-cross-user). */
    public Optional<AssetRow> findByIdAndOwner(String id, UUID userId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getId, id)
                .eq(AssetEntity::getUserId, userId.toString())))
                .map(AssetRepository::toRow);
    }

    /**
     * Filtered, owner-scoped, paginated search.
     *
     * @param projectId null/blank = all projects; {@code __unclassified__} = project_id IS NULL
     * @param source    sourceKind filter (null/blank = all)
     * @param q         free-text over name/note/sourceKind/sourceLabel/prompt
     * @param tag       tags array contains (JSONB {@code ??} operator)
     * @param sort      newest | oldest | used
     */
    public AssetPage search(UUID userId, String projectId, String source, String q, String tag,
                            String sort, int page, int size) {
        LambdaQueryWrapper<AssetEntity> wrapper = new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getUserId, userId.toString());
        if (projectId != null && !projectId.isBlank()) {
            if ("__unclassified__".equals(projectId)) {
                wrapper.isNull(AssetEntity::getProjectId);
            } else {
                wrapper.eq(AssetEntity::getProjectId, projectId);
            }
        }
        if (source != null && !source.isBlank()) {
            wrapper.eq(AssetEntity::getSourceKind, source);
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            wrapper.and(w -> w.like(AssetEntity::getName, like)
                    .or().like(AssetEntity::getNote, like)
                    .or().like(AssetEntity::getSourceKind, like)
                    .or().like(AssetEntity::getSourceLabel, like)
                    .or().like(AssetEntity::getPrompt, like));
        }
        if (tag != null && !tag.isBlank()) {
            wrapper.apply("tags ?? {0}", tag.trim());
        }
        switch (sort == null ? "newest" : sort) {
            case "oldest" -> wrapper.orderByAsc(AssetEntity::getCreatedAt);
            case "used" -> wrapper.orderByDesc(AssetEntity::getLastUsedAt)
                    .orderByDesc(AssetEntity::getCreatedAt);
            default -> wrapper.orderByDesc(AssetEntity::getCreatedAt);
        }

        Long total = mapper.selectCount(wrapper);
        int offset = Math.max(0, (page - 1) * size);
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        List<AssetRow> items = mapper.selectList(wrapper).stream()
                .map(AssetRepository::toRow).toList();
        return new AssetPage(items, total == null ? 0 : total);
    }

    /** Inserts an asset; returns the generated id. */
    public String insert(AssetEntity entity) {
        mapper.insert(entity);
        return entity.getId();
    }

    /** Selective update (name/tags/note/projectId/width/height + touched timestamps). */
    public void update(AssetEntity patch) {
        mapper.updateById(patch);
    }

    public void delete(String id) {
        mapper.deleteById(id);
    }

    /** Batch delete by owner (used by batch-delete + project cascade). */
    public int deleteByIdsAndOwner(UUID userId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return mapper.delete(new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getUserId, userId.toString())
                .in(AssetEntity::getId, ids));
    }

    public long countByProject(UUID userId, String projectId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getUserId, userId.toString())
                .eq(AssetEntity::getProjectId, projectId));
        return count == null ? 0 : count;
    }

    public Instant lastActivityByProject(UUID userId, String projectId) {
        List<AssetEntity> rows = mapper.selectList(new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getUserId, userId.toString())
                .eq(AssetEntity::getProjectId, projectId)
                .orderByDesc(AssetEntity::getCreatedAt)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0).getCreatedAt();
    }

    /** All rows of a project (cascade delete reads storage keys first). */
    public List<AssetRow> findByProject(UUID userId, String projectId) {
        return mapper.selectList(new LambdaQueryWrapper<AssetEntity>()
                        .eq(AssetEntity::getUserId, userId.toString())
                        .eq(AssetEntity::getProjectId, projectId))
                .stream().map(AssetRepository::toRow).toList();
    }

    /** Same-hash duplicate within (user, project) — R-5 dedup hint. */
    public Optional<AssetRow> findDuplicate(UUID userId, String projectId, String hash) {
        if (hash == null || hash.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getUserId, userId.toString())
                .eq(AssetEntity::getProjectId, projectId)
                .eq(AssetEntity::getHash, hash)
                .last("LIMIT 1")))
                .map(AssetRepository::toRow);
    }

    private static AssetRow toRow(AssetEntity e) {
        return new AssetRow(e.getId(), e.getUserId(), e.getProjectId(), e.getKind(), e.getName(),
                e.getMimeType(), e.getSizeBytes(), e.getWidth(), e.getHeight(),
                e.getTags(), e.getNote(), e.getSourceKind(), e.getSourceLabel(),
                e.getSourceRef(), e.getPrompt(), e.getStorageKey(), e.getHash(),
                e.getCreatedAt(), e.getUpdatedAt(), e.getLastUsedAt());
    }
}
