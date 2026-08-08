package com.nova.studio.history;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * WIN-39 (ADR-39) — {@code histories} table access（统一历史 reverse/gif；
 * 迁移导入 + 后续 T12/T13 使用）。幂等：按 id+owner 存在即跳过。
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

    public Optional<HistoryEntity> findByIdAndOwner(String id, UUID userId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<HistoryEntity>()
                .eq(HistoryEntity::getId, id)
                .eq(HistoryEntity::getUserId, userId.toString())));
    }
}
