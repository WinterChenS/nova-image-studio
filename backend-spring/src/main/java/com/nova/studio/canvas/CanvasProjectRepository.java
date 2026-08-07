package com.nova.studio.canvas;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WIN-39 (WIN-40 T5) — {@code canvas_projects} table access. Every query
 * filters by {@code user_id}（AC-10）。列表默认排除软删（回收站）。
 */
@Repository
public class CanvasProjectRepository {

    /** Public row shape (service/test contract). */
    public record CanvasRow(String id, String userId, String title, String nodes, String connections,
                            String backgroundMode, Boolean showImageInfo, String viewport,
                            Long version, Instant deletedAt,
                            Instant createdAt, Instant updatedAt) {
    }

    private final CanvasProjectMapper mapper;

    public CanvasProjectRepository(CanvasProjectMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<CanvasRow> findByIdAndOwner(String id, UUID userId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<CanvasProjectEntity>()
                .eq(CanvasProjectEntity::getId, id)
                .eq(CanvasProjectEntity::getUserId, userId.toString())))
                .map(CanvasProjectRepository::toRow);
    }

    /** 项目列表（updated_at DESC）；includeDeleted=true 显示回收站。 */
    public List<CanvasRow> list(UUID userId, boolean includeDeleted) {
        LambdaQueryWrapper<CanvasProjectEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CanvasProjectEntity::getUserId, userId.toString());
        if (!includeDeleted) {
            wrapper.isNull(CanvasProjectEntity::getDeletedAt);
        }
        wrapper.orderByDesc(CanvasProjectEntity::getUpdatedAt);
        return mapper.selectList(wrapper).stream().map(CanvasProjectRepository::toRow).toList();
    }

    public long countActive(UUID userId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<CanvasProjectEntity>()
                .eq(CanvasProjectEntity::getUserId, userId.toString())
                .isNull(CanvasProjectEntity::getDeletedAt));
        return count == null ? 0 : count;
    }

    public String insert(CanvasProjectEntity entity) {
        mapper.insert(entity);
        return entity.getId();
    }

    /** 整文档更新（PUT）：nodes/connections/viewport/背景 + version 自增。 */
    public void saveDocument(CanvasProjectEntity patch) {
        mapper.updateById(patch);
    }

    /** 轻量字段更新（PATCH）。 */
    public void patch(CanvasProjectEntity patch) {
        mapper.updateById(patch);
    }

    /** 软删（回收站，C8）。 */
    public void softDelete(String id, Instant now) {
        mapper.update(null, new LambdaUpdateWrapper<CanvasProjectEntity>()
                .eq(CanvasProjectEntity::getId, id)
                .set(CanvasProjectEntity::getDeletedAt, now)
                .set(CanvasProjectEntity::getUpdatedAt, now));
    }

    /** 恢复（清 deleted_at）。 */
    public void restore(String id) {
        mapper.update(null, new LambdaUpdateWrapper<CanvasProjectEntity>()
                .eq(CanvasProjectEntity::getId, id)
                .set(CanvasProjectEntity::getDeletedAt, null)
                .set(CanvasProjectEntity::getUpdatedAt, Instant.now()));
    }

    private static CanvasRow toRow(CanvasProjectEntity e) {
        return new CanvasRow(e.getId(), e.getUserId(), e.getTitle(), e.getNodes(), e.getConnections(),
                e.getBackgroundMode(), e.getShowImageInfo(), e.getViewport(), e.getVersion(),
                e.getDeletedAt(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
