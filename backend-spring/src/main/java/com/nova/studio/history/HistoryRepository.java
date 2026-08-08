package com.nova.studio.history;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WIN-39 (ADR-39 + WIN-41 T12/T13) — {@code histories} table access（统一历史
 * reverse/gif）：类型分页、状态查询、更新、删除、草稿唯一、超量滚动清理。
 */
@Repository
public class HistoryRepository {

    private final HistoryMapper mapper;

    public HistoryRepository(HistoryMapper mapper) {
        this.mapper = mapper;
    }

    public boolean existsByIdAndOwner(String id, UUID userId) {
        return mapper.selectCount(new LambdaQueryWrapper<HistoryEntity>()
                .eq(HistoryEntity::getId, id)
                .eq(HistoryEntity::getUserId, userId.toString())) > 0;
    }

    public String insert(HistoryEntity entity) {
        mapper.insert(entity);
        return entity.getId();
    }

    public void update(HistoryEntity entity) {
        mapper.updateById(entity);
    }

    public int deleteByIdAndOwner(String id, UUID userId) {
        return mapper.delete(new LambdaQueryWrapper<HistoryEntity>()
                .eq(HistoryEntity::getId, id)
                .eq(HistoryEntity::getUserId, userId.toString()));
    }

    public Optional<HistoryEntity> findByIdAndOwner(String id, UUID userId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<HistoryEntity>()
                .eq(HistoryEntity::getId, id)
                .eq(HistoryEntity::getUserId, userId.toString())));
    }

    /** 按类型分页（created_at DESC，before 游标）。 */
    public HistoryPage listByType(UUID userId, String type, Instant before, int limit) {
        int safeLimit = Math.min(Math.max(limit <= 0 ? 50 : limit, 1), 200);
        LambdaQueryWrapper<HistoryEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(HistoryEntity::getUserId, userId.toString())
                .eq(HistoryEntity::getType, type);
        if (before != null) {
            wrapper.lt(HistoryEntity::getCreatedAt, before);
        }
        wrapper.orderByDesc(HistoryEntity::getCreatedAt)
                .last("LIMIT " + safeLimit);
        List<HistoryEntity> rows = mapper.selectList(wrapper);
        String nextBefore = rows.isEmpty() ? null : rows.get(rows.size() - 1).getCreatedAt().toString();
        return new HistoryPage(rows.stream().map(HistoryRepository::toRow).toList(), nextBefore);
    }

    /** 最近 limit 条 completed（反推双槽语义，ADR-39）。 */
    public List<HistoryEntity> listCompletedRecent(UUID userId, String type, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<HistoryEntity>()
                .eq(HistoryEntity::getUserId, userId.toString())
                .eq(HistoryEntity::getType, type)
                .eq(HistoryEntity::getStatus, "completed")
                .orderByDesc(HistoryEntity::getCreatedAt)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 50)));
    }

    /** 反推草稿（每用户至多一条，partial unique index 兜底）。 */
    public Optional<HistoryEntity> findReverseDraft(UUID userId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<HistoryEntity>()
                .eq(HistoryEntity::getUserId, userId.toString())
                .eq(HistoryEntity::getType, "reverse")
                .eq(HistoryEntity::getStatus, "draft")
                .last("LIMIT 1")));
    }

    /** 删除最旧超量记录（滚动清理，ADR-42）。返回删除条数。 */
    public int deleteOldestBeyondCap(UUID userId, String type, int cap) {
        long count = mapper.selectCount(new LambdaQueryWrapper<HistoryEntity>()
                .eq(HistoryEntity::getUserId, userId.toString())
                .eq(HistoryEntity::getType, type));
        if (count <= cap) {
            return 0;
        }
        long excess = count - cap;
        List<HistoryEntity> oldest = mapper.selectList(new LambdaQueryWrapper<HistoryEntity>()
                .eq(HistoryEntity::getUserId, userId.toString())
                .eq(HistoryEntity::getType, type)
                .orderByAsc(HistoryEntity::getCreatedAt)
                .last("LIMIT " + excess));
        int deleted = 0;
        for (HistoryEntity entity : oldest) {
            deleted += mapper.deleteById(entity.getId());
        }
        return deleted;
    }

    /** Public row shape. */
    public record HistoryRow(String id, String userId, String type, String status, String title,
                             String payload, String imageIds, String taskId, String error,
                             Instant createdAt, Instant updatedAt) {
    }

    public record HistoryPage(List<HistoryRow> items, String nextBefore) {
    }

    public static HistoryRow toRow(HistoryEntity entity) {
        return new HistoryRow(entity.getId(), entity.getUserId(), entity.getType(), entity.getStatus(),
                entity.getTitle(), entity.getPayload(), entity.getImageIds(), entity.getTaskId(),
                entity.getError(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
