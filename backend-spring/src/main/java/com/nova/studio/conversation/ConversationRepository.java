package com.nova.studio.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WIN-39 (WIN-40 T3) — {@code conversations} table access. Every query filters
 * by {@code user_id} (AC-10 属主隔离). List ordering: last_message_at DESC.
 */
@Repository
public class ConversationRepository {

    /** Public row shape (service/test contract). */
    public record ConversationRow(String id, String userId, String title, String status,
                                  String imageModel, Boolean webSearch, String pending,
                                  String contextSummary, Instant deletedAt,
                                  Instant createdAt, Instant updatedAt, Instant lastMessageAt) {
    }

    /** A page of conversations (last_message_at DESC) + next-page cursor. */
    public record ConversationPage(List<ConversationRow> items, String nextBefore) {
    }

    private final ConversationMapper mapper;

    public ConversationRepository(ConversationMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<ConversationRow> findByIdAndOwner(String id, UUID userId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getId, id)
                .eq(ConversationEntity::getUserId, userId.toString())))
                .map(ConversationRepository::toRow);
    }

    /**
     * id 全局是否存在（跨用户）——迁移导入的跨用户冲突检测（WIN-44 BUG-3）：
     * 硬编码 id 已被其他用户占用时，服务端据此重新生成唯一 id 而非主键冲突失败。
     */
    public boolean existsById(String id) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getId, id));
        return count != null && count > 0;
    }

    /**
     * Owner-scoped conversation list, last_message_at DESC.
     *
     * @param status active|archived|deleted|recycle（deleted+archived）；null/blank = 全部非 deleted
     * @param before 游标：仅返回 last_message_at < before 的会话（分页，FR-1.3 懒加载）
     */
    public ConversationPage list(UUID userId, String status, Instant before, int limit) {
        LambdaQueryWrapper<ConversationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConversationEntity::getUserId, userId.toString());
        if ("recycle".equals(status)) {
            wrapper.eq(ConversationEntity::getStatus, "deleted");
        } else if (status != null && !status.isBlank() && !"all".equals(status)) {
            wrapper.eq(ConversationEntity::getStatus, status);
        } else {
            wrapper.ne(ConversationEntity::getStatus, "deleted");   // 默认不含回收站
        }
        if (before != null) {
            wrapper.lt(ConversationEntity::getLastMessageAt, before);
        }
        wrapper.orderByDesc(ConversationEntity::getLastMessageAt)
                .orderByDesc(ConversationEntity::getCreatedAt)
                .last("LIMIT " + Math.min(Math.max(limit <= 0 ? 50 : limit, 1), 200));
        List<ConversationRow> rows = mapper.selectList(wrapper).stream()
                .map(ConversationRepository::toRow).toList();
        String nextBefore = rows.isEmpty() ? null : rows.get(rows.size() - 1).lastMessageAt() == null
                ? null : rows.get(rows.size() - 1).lastMessageAt().toString();
        return new ConversationPage(rows, nextBefore);
    }

    public long countActive(UUID userId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getUserId, userId.toString())
                .in(ConversationEntity::getStatus, "active", "archived"));
        return count == null ? 0 : count;
    }

    public String insert(ConversationEntity entity) {
        mapper.insert(entity);
        return entity.getId();
    }

    /** Selective update (title/status/imageModel/webSearch/pending/contextSummary/deletedAt/lastMessageAt).
     *  注意：updateById 跳过 null 字段，清空 JSONB 列需走下方显式 clear 方法。 */
    public void update(ConversationEntity patch) {
        mapper.updateById(patch);
    }

    /** 清空 pending（恢复完成后，FR-1.2）。 */
    public void clearPending(String id) {
        mapper.update(null, new LambdaUpdateWrapper<ConversationEntity>()
                .eq(ConversationEntity::getId, id)
                .set(ConversationEntity::getPending, null)
                .set(ConversationEntity::getUpdatedAt, Instant.now()));
    }

    /** 清空 context_summary（可选）。 */
    public void clearContextSummary(String id) {
        mapper.update(null, new LambdaUpdateWrapper<ConversationEntity>()
                .eq(ConversationEntity::getId, id)
                .set(ConversationEntity::getContextSummary, null)
                .set(ConversationEntity::getUpdatedAt, Instant.now()));
    }

    /** 清空 deleted_at（回收站恢复，C8）。 */
    public void clearDeletedAt(String id) {
        mapper.update(null, new LambdaUpdateWrapper<ConversationEntity>()
                .eq(ConversationEntity::getId, id)
                .set(ConversationEntity::getDeletedAt, null)
                .set(ConversationEntity::getUpdatedAt, Instant.now()));
    }

    /** 清空 image_model（可选）。 */
    public void clearImageModel(String id) {
        mapper.update(null, new LambdaUpdateWrapper<ConversationEntity>()
                .eq(ConversationEntity::getId, id)
                .set(ConversationEntity::getImageModel, null)
                .set(ConversationEntity::getUpdatedAt, Instant.now()));
    }

    public int delete(String id) {
        return mapper.deleteById(id);
    }

    /** 回收站清理（每日任务）：status=deleted 到期硬删。 */
    public List<ConversationRow> listDeletedOlderThan(UUID userId, Instant before) {
        return mapper.selectList(new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getUserId, userId.toString())
                        .eq(ConversationEntity::getStatus, "deleted")
                        .lt(ConversationEntity::getDeletedAt, before))
                .stream().map(ConversationRepository::toRow).toList();
    }

    /** 回收站全部软删会话（T16「清空回收站」）。 */
    public List<ConversationRow> listDeleted(UUID userId) {
        return mapper.selectList(new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getUserId, userId.toString())
                        .eq(ConversationEntity::getStatus, "deleted"))
                .stream().map(ConversationRepository::toRow).toList();
    }

    public int delete(String id, UUID userId) {
        return mapper.delete(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getId, id)
                .eq(ConversationEntity::getUserId, userId.toString()));
    }

    private static ConversationRow toRow(ConversationEntity e) {
        return new ConversationRow(e.getId(), e.getUserId(), e.getTitle(), e.getStatus(),
                e.getImageModel(), e.getWebSearch(), e.getPending(), e.getContextSummary(),
                e.getDeletedAt(), e.getCreatedAt(), e.getUpdatedAt(), e.getLastMessageAt());
    }
}
