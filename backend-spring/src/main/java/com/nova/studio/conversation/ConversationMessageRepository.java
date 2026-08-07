package com.nova.studio.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WIN-39 (WIN-40 T3) — {@code conversation_messages} table access. Primary
 * access path (conversation_id, created_at)（F.5）；user_id 供用户级清理/审计。
 * 分页：首屏最近 N 条 + before 游标加载更早（FR-1.3 懒加载）。
 */
@Repository
public class ConversationMessageRepository {

    /** Public row shape (service/test contract). */
    public record MessageRow(String id, String conversationId, String userId, String role,
                             String text, String reasoning, String imageIds, String taskId,
                             String proposalData, Boolean webSearchUsed, Boolean withdrawable,
                             Instant createdAt) {
    }

    /** A page of messages (created_at DESC) + next-page cursor. */
    public record MessagePage(List<MessageRow> items, String nextBefore) {
    }

    private final ConversationMessageMapper mapper;

    public ConversationMessageRepository(ConversationMessageMapper mapper) {
        this.mapper = mapper;
    }

    /** 最近 limit 条（created_at DESC），或 before 游标之前的更早消息。 */
    public MessagePage listByConversation(String conversationId, String userId, Instant before, int limit) {
        int safeLimit = Math.min(Math.max(limit <= 0 ? 50 : limit, 1), 200);
        LambdaQueryWrapper<ConversationMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConversationMessageEntity::getConversationId, conversationId)
                .eq(ConversationMessageEntity::getUserId, userId.toString());
        if (before != null) {
            wrapper.lt(ConversationMessageEntity::getCreatedAt, before);
        }
        wrapper.orderByDesc(ConversationMessageEntity::getCreatedAt)
                .last("LIMIT " + safeLimit);
        List<MessageRow> rows = mapper.selectList(wrapper).stream()
                .map(ConversationMessageRepository::toRow).toList();
        String nextBefore = rows.isEmpty() ? null : rows.get(rows.size() - 1).createdAt().toString();
        return new MessagePage(rows, nextBefore);
    }

    public long countByConversation(String conversationId, String userId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<ConversationMessageEntity>()
                .eq(ConversationMessageEntity::getConversationId, conversationId)
                .eq(ConversationMessageEntity::getUserId, userId.toString()));
        return count == null ? 0 : count;
    }

    public Optional<MessageRow> findByIdAndOwner(String id, String conversationId, UUID userId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<ConversationMessageEntity>()
                .eq(ConversationMessageEntity::getId, id)
                .eq(ConversationMessageEntity::getConversationId, conversationId)
                .eq(ConversationMessageEntity::getUserId, userId.toString())))
                .map(ConversationMessageRepository::toRow);
    }

    public String insert(ConversationMessageEntity entity) {
        mapper.insert(entity);
        return entity.getId();
    }

    public int deleteByIdsAndOwner(List<String> ids, String conversationId, UUID userId) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return mapper.delete(new LambdaQueryWrapper<ConversationMessageEntity>()
                .eq(ConversationMessageEntity::getConversationId, conversationId)
                .eq(ConversationMessageEntity::getUserId, userId.toString())
                .in(ConversationMessageEntity::getId, ids));
    }

    /** 会话软删后级联清消息（每日清理任务 / 硬删路径）。 */
    public int deleteByConversation(String conversationId, UUID userId) {
        return mapper.delete(new LambdaQueryWrapper<ConversationMessageEntity>()
                .eq(ConversationMessageEntity::getConversationId, conversationId)
                .eq(ConversationMessageEntity::getUserId, userId.toString()));
    }

    private static MessageRow toRow(ConversationMessageEntity e) {
        return new MessageRow(e.getId(), e.getConversationId(), e.getUserId(), e.getRole(),
                e.getText(), e.getReasoning(), e.getImageIds(), e.getTaskId(),
                e.getProposalData(), e.getWebSearchUsed(), e.getWithdrawable(), e.getCreatedAt());
    }
}
