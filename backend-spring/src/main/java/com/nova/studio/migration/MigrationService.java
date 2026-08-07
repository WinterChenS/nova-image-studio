package com.nova.studio.migration;

import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.canvas.CanvasProjectEntity;
import com.nova.studio.canvas.CanvasProjectRepository;
import com.nova.studio.conversation.ConversationEntity;
import com.nova.studio.conversation.ConversationMessageEntity;
import com.nova.studio.conversation.ConversationRepository;
import com.nova.studio.conversation.ConversationMessageRepository;
import com.nova.studio.history.HistoryEntity;
import com.nova.studio.history.HistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WIN-39 (WIN-40 T7) — 迁移框架后端：本地存量按功能批量导入 + 幂等去重
 * （业务唯一键：conversation_id / project.id / histories 客户端 id / asset hash）+ 
 * upload-image 图片分批上传（→ assets，返回 assetId 供 JSON 改写引用）。
 *
 * <p>幂等语义（FR-7.2/7.3）：已存在的唯一键（同 user_id）直接跳过，可重试不报错；
 * 图片上传复用 assets 既有 hash 去重（同 hash → 409 由前端捕获跳过）。
 */
@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final CanvasProjectRepository canvasRepository;
    private final HistoryRepository historyRepository;
    private final AssetService assetService;

    public MigrationService(ConversationRepository conversationRepository,
                            ConversationMessageRepository messageRepository,
                            CanvasProjectRepository canvasRepository,
                            HistoryRepository historyRepository,
                            AssetService assetService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.canvasRepository = canvasRepository;
        this.historyRepository = historyRepository;
        this.assetService = assetService;
    }

    /** 迁移结果摘要（幂等计数）。 */
    public record MigrationSummary(int created, int skipped, int failed) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("created", created);
            map.put("skipped", skipped);
            map.put("failed", failed);
            return map;
        }
    }

    // ===== Agent 会话迁移（幂等：conversation_id 去重）=====

    public MigrationSummary importConversations(UUID userId, JsonNode body) {
        if (body == null || !body.isObject() || !body.has("conversations") || !body.get("conversations").isArray()) {
            throw new IllegalArgumentException("请求体需包含 conversations 数组");
        }
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (JsonNode convNode : body.get("conversations")) {
            try {
                String convId = convNode.hasNonNull("id") ? convNode.get("id").asText() : UUID.randomUUID().toString();
                if (conversationRepository.findByIdAndOwner(convId, userId).isPresent()) {
                    skipped++;
                    continue;
                }
                Instant now = Instant.now();
                ConversationEntity conv = new ConversationEntity();
                conv.setId(convId);
                conv.setUserId(userId.toString());
                conv.setTitle(convNode.hasNonNull("title") ? convNode.get("title").asText() : "未命名会话");
                conv.setStatus(convNode.hasNonNull("status")
                        && List.of("active", "archived", "deleted").contains(convNode.get("status").asText())
                        ? convNode.get("status").asText() : "active");
                conv.setImageModel(convNode.hasNonNull("imageModel") ? convNode.get("imageModel").asText() : null);
                conv.setWebSearch(convNode.hasNonNull("webSearch") && convNode.get("webSearch").asBoolean());
                conv.setPending(nodeToJsonOrNull(convNode.get("pending")));
                conv.setContextSummary(nodeToJsonOrNull(convNode.get("contextSummary")));
                if (convNode.hasNonNull("createdAt")) {
                    conv.setCreatedAt(parseInstant(convNode.get("createdAt").asText(), now));
                } else {
                    conv.setCreatedAt(now);
                }
                conv.setUpdatedAt(convNode.hasNonNull("updatedAt")
                        ? parseInstant(convNode.get("updatedAt").asText(), now) : now);
                conversationRepository.insert(conv);

                // 消息
                if (convNode.has("messages") && convNode.get("messages").isArray()) {
                    for (JsonNode msgNode : convNode.get("messages")) {
                        ConversationMessageEntity msg = new ConversationMessageEntity();
                        msg.setId(msgNode.hasNonNull("id") ? msgNode.get("id").asText()
                                : UUID.randomUUID().toString());
                        msg.setConversationId(convId);
                        msg.setUserId(userId.toString());
                        msg.setRole(msgNode.hasNonNull("role") ? msgNode.get("role").asText() : "user");
                        msg.setText(msgNode.hasNonNull("text") ? msgNode.get("text").asText() : "");
                        msg.setReasoning(msgNode.hasNonNull("reasoning") ? msgNode.get("reasoning").asText() : null);
                        msg.setImageIds(nodeToJsonOrNull(msgNode.get("imageIds")));
                        msg.setTaskId(msgNode.hasNonNull("taskId") ? msgNode.get("taskId").asText() : null);
                        msg.setProposalData(nodeToJsonOrNull(msgNode.get("proposalData")));
                        msg.setWebSearchUsed(msgNode.hasNonNull("webSearchUsed")
                                ? msgNode.get("webSearchUsed").asBoolean() : null);
                        msg.setWithdrawable(msgNode.hasNonNull("withdrawable")
                                && msgNode.get("withdrawable").asBoolean());
                        msg.setCreatedAt(msgNode.hasNonNull("createdAt")
                                ? parseInstant(msgNode.get("createdAt").asText(), now) : now);
                        messageRepository.insert(msg);
                    }
                }
                created++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("[migration] 会话导入失败: user={}, conv={}: {}", userId,
                        convNode.get("id"), e.getMessage());
            }
        }
        log.info("[migration] Agent 会话导入: user={}, created={}, skipped={}, failed={}",
                userId, created, skipped, failed);
        return new MigrationSummary(created, skipped, failed);
    }

    // ===== 画布迁移（幂等：project.id 去重；节点引用由前端先改写为 assetId）=====

    public MigrationSummary importCanvasProjects(UUID userId, JsonNode body) {
        if (body == null || !body.isObject() || !body.has("projects") || !body.get("projects").isArray()) {
            throw new IllegalArgumentException("请求体需包含 projects 数组");
        }
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (JsonNode projectNode : body.get("projects")) {
            try {
                String projectId = projectNode.hasNonNull("id")
                        ? projectNode.get("id").asText() : UUID.randomUUID().toString();
                if (canvasRepository.findByIdAndOwner(projectId, userId).isPresent()) {
                    skipped++;
                    continue;
                }
                Instant now = Instant.now();
                CanvasProjectEntity entity = new CanvasProjectEntity();
                entity.setId(projectId);
                entity.setUserId(userId.toString());
                entity.setTitle(projectNode.hasNonNull("title") ? projectNode.get("title").asText() : "未命名画布");
                entity.setNodes(nodeToJsonOrNull(projectNode.get("nodes")) == null ? "[]"
                        : nodeToJsonOrNull(projectNode.get("nodes")));
                entity.setConnections(nodeToJsonOrNull(projectNode.get("connections")) == null ? "[]"
                        : nodeToJsonOrNull(projectNode.get("connections")));
                entity.setBackgroundMode(projectNode.hasNonNull("backgroundMode")
                        ? projectNode.get("backgroundMode").asText() : "lines");
                entity.setShowImageInfo(projectNode.hasNonNull("showImageInfo")
                        && projectNode.get("showImageInfo").asBoolean());
                entity.setViewport(nodeToJsonOrNull(projectNode.get("viewport")) == null
                        ? "{\"x\":0,\"y\":0,\"k\":1}" : nodeToJsonOrNull(projectNode.get("viewport")));
                entity.setVersion(projectNode.hasNonNull("version")
                        ? projectNode.get("version").asLong() : 1L);
                entity.setCreatedAt(projectNode.hasNonNull("createdAt")
                        ? parseInstant(projectNode.get("createdAt").asText(), now) : now);
                entity.setUpdatedAt(projectNode.hasNonNull("updatedAt")
                        ? parseInstant(projectNode.get("updatedAt").asText(), now) : now);
                canvasRepository.insert(entity);
                created++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("[migration] 画布导入失败: user={}, project={}: {}", userId,
                        projectNode.get("id"), e.getMessage());
            }
        }
        log.info("[migration] 画布导入: user={}, created={}, skipped={}, failed={}",
                userId, created, skipped, failed);
        return new MigrationSummary(created, skipped, failed);
    }

    // ===== 反推 / GIF 迁移（histories 统一表，幂等：客户端 id 去重）=====

    public MigrationSummary importHistories(UUID userId, String type, JsonNode body) {
        if (body == null || !body.isObject() || !body.has("items") || !body.get("items").isArray()) {
            throw new IllegalArgumentException("请求体需包含 items 数组");
        }
        if (!List.of("reverse", "gif").contains(type)) {
            throw new IllegalArgumentException("历史类型无效");
        }
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (JsonNode item : body.get("items")) {
            try {
                String id = item.hasNonNull("id") ? item.get("id").asText() : UUID.randomUUID().toString();
                if (historyRepository.existsByIdAndOwner(id, userId)) {
                    skipped++;
                    continue;
                }
                Instant now = Instant.now();
                HistoryEntity entity = new HistoryEntity();
                entity.setId(id);
                entity.setUserId(userId.toString());
                entity.setType(type);
                entity.setStatus(item.hasNonNull("status") ? item.get("status").asText() : "completed");
                entity.setTitle(item.hasNonNull("title") ? item.get("title").asText() : null);
                entity.setPayload(nodeToJsonOrNull(item.get("payload")) == null ? "{}"
                        : nodeToJsonOrNull(item.get("payload")));
                entity.setImageIds(nodeToJsonOrNull(item.get("imageIds")) == null ? "[]"
                        : nodeToJsonOrNull(item.get("imageIds")));
                entity.setTaskId(item.hasNonNull("taskId") ? item.get("taskId").asText() : null);
                entity.setError(item.hasNonNull("error") ? item.get("error").asText() : null);
                entity.setCreatedAt(item.hasNonNull("createdAt")
                        ? parseInstant(item.get("createdAt").asText(), now) : now);
                entity.setUpdatedAt(now);
                historyRepository.insert(entity);
                created++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("[migration] {} 历史导入失败: user={}, item={}: {}", type, userId,
                        item.get("id"), e.getMessage());
            }
        }
        log.info("[migration] {} 历史导入: user={}, created={}, skipped={}, failed={}",
                type, userId, created, skipped, failed);
        return new MigrationSummary(created, skipped, failed);
    }

    // ===== 图片分批上传（→ assets，返回 assetId 供 JSON 改写引用）=====

    /**
     * 图片字节分批上传：sourceKind=canvas|conversation|reverse-prompt|gif，
     * 复用 assets hash 去重（同 hash 同用户 → 409 由前端捕获跳过，FR-7.2 幂等）。
     */
    public AssetRepository.AssetRow uploadImage(UUID userId, byte[] bytes, String mimeType,
                                                String sourceKind, String sourceRef, String name) {
        String normalizedKind = sourceKind == null ? "canvas" : sourceKind;
        if (!AssetService.SOURCE_KINDS.contains(normalizedKind)) {
            throw new IllegalArgumentException("来源分类无效");
        }
        return assetService.createImage(userId, null, name, List.of(), null,
                normalizedKind, "迁移导入", sourceRef, null,
                bytes, mimeType, null, null, Instant.now(), "{}");
    }

    // ===== helpers =====

    private static String nodeToJsonOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    private static Instant parseInstant(String value, Instant fallback) {
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return fallback;
        }
    }
}
