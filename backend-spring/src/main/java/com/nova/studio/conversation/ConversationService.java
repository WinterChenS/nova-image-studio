package com.nova.studio.conversation;

import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WIN-39 (WIN-40 T3) — Agent 会话持久化服务：conversations CRUD + 消息分页 +
 * pending/context_summary 读写 + 会话图片目录（assets source_kind='conversation'）+
 * title 自动摘要 + 配额校验（limit.agentConversationCap / limit.agentMessageCapPerConversation）。
 *
 * <p>属主隔离：全部读取/变更先经 {@code findByIdAndOwner}（跨用户 404，AC-10）；
 * 配额超限 → 409 QUOTA_EXCEEDED（AC-11 基础）。阶段1 为纯存储 API；阶段2（T9）
 * 将消息发送升级为后端 SSE 托管，本服务接口保持不变。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_ARCHIVED = "archived";
    public static final String STATUS_DELETED = "deleted";
    public static final String DEFAULT_TITLE = "未命名会话";
    public static final String SOURCE_KIND_CONVERSATION = "conversation";

    /** ARCH Part F.4 配额默认值（settings limit.* 可覆盖）。 */
    public static final int DEFAULT_CONVERSATION_CAP = SettingsService.DEFAULT_AGENT_CONVERSATION_CAP;
    public static final int DEFAULT_MESSAGE_CAP_PER_CONVERSATION = SettingsService.DEFAULT_AGENT_MESSAGE_CAP;

    private final ConversationRepository repository;
    private final ConversationMessageRepository messageRepository;
    private final AssetService assetService;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    public ConversationService(ConversationRepository repository,
                               ConversationMessageRepository messageRepository,
                               AssetService assetService,
                               SettingsService settingsService,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.messageRepository = messageRepository;
        this.assetService = assetService;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    // ===== conversations =====

    /** 新建会话（可选 title；配额校验 AC-11）。 */
    public ConversationRepository.ConversationRow create(UUID userId, String title) {
        int cap = settingsService.getInt(userId, SettingsService.KEY_AGENT_CONVERSATION_CAP, DEFAULT_CONVERSATION_CAP);
        long active = repository.countActive(userId);
        if (active >= cap) {
            throw new HttpErrorException(409, "QUOTA_EXCEEDED",
                    "会话数量已达上限（" + cap + "），请归档或删除旧会话");
        }
        Instant now = Instant.now();
        ConversationEntity entity = new ConversationEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId.toString());
        entity.setTitle(title != null && !title.isBlank() ? title.trim() : DEFAULT_TITLE);
        entity.setStatus(STATUS_ACTIVE);
        entity.setWebSearch(false);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        repository.insert(entity);
        log.info("[conversation] 新建会话: user={}, conversation={}", userId, entity.getId());
        return repository.findByIdAndOwner(entity.getId(), userId).orElseThrow();
    }

    public ConversationRepository.ConversationPage list(UUID userId, String status, String before, int limit) {
        Instant cursor = parseCursor(before);
        return repository.list(userId, status, cursor, limit);
    }

    /** 会话详情（含 pending/context_summary 与图片目录摘要）。 */
    public Map<String, Object> detail(UUID userId, String conversationId) {
        ConversationRepository.ConversationRow row = getOwned(userId, conversationId);
        Map<String, Object> map = toJson(row);
        List<AssetRepository.AssetRow> images = assetService.listConversationImages(userId, conversationId);
        List<Map<String, Object>> imageSummary = new ArrayList<>();
        for (AssetRepository.AssetRow image : images) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("assetId", image.id());
            item.put("source", parseExtraField(image.extra(), "catalogSource", "uploaded"));
            item.put("description", parseExtraField(image.extra(), "description", null));
            item.put("mimeType", image.mimeType());
            item.put("width", image.width());
            item.put("height", image.height());
            imageSummary.add(item);
        }
        map.put("images", imageSummary);
        return map;
    }

    /**
     * 会话字段更新：title / status（归档/恢复/软删）/ imageModel / webSearch /
     * pending（进行中恢复态，FR-1.2）/ contextSummary（压缩摘要，ADR-44）。
     */
    public ConversationRepository.ConversationRow patch(UUID userId, String conversationId, JsonNode body) {
        ConversationRepository.ConversationRow row = getOwned(userId, conversationId);
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        ConversationEntity patch = new ConversationEntity();
        patch.setId(conversationId);
        patch.setUpdatedAt(Instant.now());
        if (body.hasNonNull("title")) {
            String title = body.get("title").asText();
            if (title.isBlank()) {
                throw new IllegalArgumentException("会话标题不能为空");
            }
            patch.setTitle(title.trim());
        }
        boolean restoreFromDeleted = false;
        if (body.hasNonNull("status")) {
            String status = body.get("status").asText();
            if (!List.of(STATUS_ACTIVE, STATUS_ARCHIVED, STATUS_DELETED).contains(status)) {
                throw new IllegalArgumentException("会话状态无效");
            }
            patch.setStatus(status);
            if (STATUS_DELETED.equals(status)) {
                patch.setDeletedAt(Instant.now());
            } else if (row.deletedAt() != null) {
                restoreFromDeleted = true;
            }
        }
        if (body.has("imageModel")) {
            if (body.get("imageModel").isNull()) {
                repository.clearImageModel(conversationId);
            } else {
                patch.setImageModel(body.get("imageModel").asText());
            }
        }
        if (body.hasNonNull("webSearch")) {
            patch.setWebSearch(body.get("webSearch").asBoolean());
        }
        if (body.has("pending")) {
            String pending = validatePending(body.get("pending"));
            if (pending == null) {
                repository.clearPending(conversationId);
            } else {
                patch.setPending(pending);
            }
        }
        if (body.has("contextSummary")) {
            String summary = validateJsonOrNull(body.get("contextSummary"));
            if (summary == null) {
                repository.clearContextSummary(conversationId);
            } else {
                patch.setContextSummary(summary);
            }
        }
        if (body.has("title") || body.has("status") || body.has("webSearch")
                || (body.has("imageModel") && !body.get("imageModel").isNull())
                || (body.has("pending") && !body.get("pending").isNull())
                || (body.has("contextSummary") && !body.get("contextSummary").isNull())) {
            repository.update(patch);
        }
        if (restoreFromDeleted) {
            repository.clearDeletedAt(conversationId);
        }
        log.info("[conversation] 更新会话: user={}, conversation={}, fields={}",
                userId, conversationId, body.size());
        return getOwned(userId, conversationId);
    }

    /** 软删 → 回收站（C8）；幂等。 */
    public void softDelete(UUID userId, String conversationId) {
        ConversationRepository.ConversationRow row = getOwned(userId, conversationId);
        if (!STATUS_DELETED.equals(row.status())) {
            ConversationEntity patch = new ConversationEntity();
            patch.setId(conversationId);
            patch.setStatus(STATUS_DELETED);
            patch.setDeletedAt(Instant.now());
            patch.setUpdatedAt(Instant.now());
            repository.update(patch);
        }
        log.info("[conversation] 软删会话: user={}, conversation={}", userId, conversationId);
    }

    /** 回收站恢复（status → active，清 deleted_at）。 */
    public void restore(UUID userId, String conversationId) {
        ConversationRepository.ConversationRow row = getOwned(userId, conversationId);
        if (!STATUS_DELETED.equals(row.status())) {
            return;   // 幂等
        }
        ConversationEntity patch = new ConversationEntity();
        patch.setId(conversationId);
        patch.setStatus(STATUS_ACTIVE);
        patch.setUpdatedAt(Instant.now());
        repository.update(patch);
        repository.clearDeletedAt(conversationId);   // updateById 跳过 null，显式清 deleted_at
        log.info("[conversation] 恢复会话: user={}, conversation={}", userId, conversationId);
    }

    /**
     * 清空回收站（T16「回收站可清空」）：硬删全部 status=deleted 会话 +
     * 级联删消息 + 引用素材 ref_count -1（尽力而为）。返回清空条数。
     */
    public int emptyTrash(UUID userId) {
        List<ConversationRepository.ConversationRow> deleted = repository.listDeleted(userId);
        int removed = 0;
        for (ConversationRepository.ConversationRow conv : deleted) {
            try {
                // 级联：先收集消息引用素材，再删消息，再删会话
                List<ConversationMessageRepository.MessageRow> messages =
                        messageRepository.listByConversation(conv.id(), userId.toString(), null, 200).items();
                List<String> assetIds = new ArrayList<>();
                for (ConversationMessageRepository.MessageRow msg : messages) {
                    assetIds.addAll(parseJsonArray(msg.imageIds()));
                }
                if (!assetIds.isEmpty()) {
                    assetService.adjustRefCounts(userId, assetIds.stream().distinct().toList(), -1);
                }
                messageRepository.deleteByConversation(conv.id(), userId);
                repository.delete(conv.id(), userId);
                removed++;
            } catch (Exception e) {
                log.warn("[conversation] 清空回收站失败（尽力而为，单条跳过）: conversation={}: {}",
                        conv.id(), e.getMessage());
            }
        }
        if (removed > 0) {
            log.info("[conversation] 清空回收站: user={}, removed={}", userId, removed);
        }
        return removed;
    }

    // ===== messages =====

    /**
     * 单条消息追加（F1 写点）：配额校验 + title 自动摘要 + lastMessageAt 联动。
     * 阶段1 纯存储；阶段2（T9）消息发送走 SSE 托管，本方法保留为落库写点。
     */
    public ConversationMessageRepository.MessageRow appendMessage(UUID userId, String conversationId, JsonNode body) {
        return appendMessage(userId, conversationId, body, null);
    }

    /**
     * 追加消息；{@code clientMessageId} 非空时作为消息主键（T9 后端会话托管：
     * SSE 发送路径以前端本地 id 落库，撤回/回滚按 id 一致）。
     */
    public ConversationMessageRepository.MessageRow appendMessage(UUID userId, String conversationId, JsonNode body,
                                                                  String clientMessageId) {
        ConversationRepository.ConversationRow conversation = getOwned(userId, conversationId);
        if (STATUS_DELETED.equals(conversation.status())) {
            throw new HttpErrorException(409, "CONVERSATION_DELETED", "会话已删除，请从回收站恢复");
        }
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String role = body.hasNonNull("role") ? body.get("role").asText() : "user";
        String text = body.hasNonNull("text") ? body.get("text").asText() : "";
        if (text.isBlank() && !body.has("imageIds")) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        int cap = settingsService.getInt(userId, SettingsService.KEY_AGENT_MESSAGE_CAP,
                DEFAULT_MESSAGE_CAP_PER_CONVERSATION);
        long count = messageRepository.countByConversation(conversationId, userId.toString());
        if (count >= cap) {
            throw new HttpErrorException(409, "QUOTA_EXCEEDED",
                    "该会话消息已达上限（" + cap + " 条），请归档会话并新建");
        }

        Instant now = Instant.now();
        ConversationMessageEntity entity = new ConversationMessageEntity();
        String messageId = clientMessageId != null && !clientMessageId.isBlank()
                ? clientMessageId : UUID.randomUUID().toString();
        if (messageRepository.existsById(messageId)) {
            messageId = UUID.randomUUID().toString();   // 主键冲突兜底（多端重放）
        }
        entity.setId(messageId);
        entity.setConversationId(conversationId);
        entity.setUserId(userId.toString());
        entity.setRole(role);
        entity.setText(text);
        entity.setReasoning(body.hasNonNull("reasoning") ? body.get("reasoning").asText() : null);
        entity.setImageIds(body.has("imageIds") ? jsonArrayOrNull(body.get("imageIds")) : null);
        entity.setTaskId(body.hasNonNull("taskId") ? body.get("taskId").asText() : null);
        entity.setProposalData(body.has("proposalData") ? validateJsonOrNull(body.get("proposalData")) : null);
        entity.setWebSearchUsed(body.hasNonNull("webSearchUsed") ? body.get("webSearchUsed").asBoolean() : null);
        entity.setWithdrawable(body.hasNonNull("withdrawable") ? body.get("withdrawable").asBoolean() : false);
        entity.setCreatedAt(now);
        messageRepository.insert(entity);

        // WIN-39 (G1 修复): 消息引用素材 → ref_count +1（引用保护，A7）
        List<String> assetIds = parseJsonArray(entity.getImageIds());
        if (!assetIds.isEmpty()) {
            assetService.adjustRefCounts(userId, assetIds, 1);
        }

        // title 自动摘要：首条用户消息截断 24 字（C1/ADR-38）
        ConversationEntity convPatch = new ConversationEntity();
        convPatch.setId(conversationId);
        convPatch.setLastMessageAt(now);
        convPatch.setUpdatedAt(now);
        if ("user".equals(role) && DEFAULT_TITLE.equals(conversation.title()) && !text.isBlank()) {
            convPatch.setTitle(truncateTitle(text));
        }
        repository.update(convPatch);

        log.info("[conversation] 追加消息: user={}, conversation={}, message={}, role={}",
                userId, conversationId, entity.getId(), role);
        return messageRepository.findByIdAndOwner(entity.getId(), conversationId, userId).orElseThrow();
    }

    /** 消息分页（默认最近 50 条，before 游标加载更早，FR-1.3）。 */
    public ConversationMessageRepository.MessagePage listMessages(UUID userId, String conversationId,
                                                                 String before, int limit) {
        getOwned(userId, conversationId);
        return messageRepository.listByConversation(conversationId, userId.toString(), parseCursor(before), limit);
    }

    /** 撤回：仅 withdrawable 消息可撤回，删除该消息及之后消息（A10 最近窗口语义）。 */
    public int withdraw(UUID userId, String conversationId, String messageId) {
        getOwned(userId, conversationId);
        ConversationMessageRepository.MessageRow message =
                messageRepository.findByIdAndOwner(messageId, conversationId, userId)
                        .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "消息不存在"));
        if (!Boolean.TRUE.equals(message.withdrawable())) {
            throw new HttpErrorException(409, "NOT_WITHDRAWABLE", "该消息不可撤回");
        }
        List<ConversationMessageRepository.MessageRow> toRemove = messageRepository
                .listByConversation(conversationId, userId.toString(),
                        null, 200).items().stream()
                .filter(m -> !m.createdAt().isBefore(message.createdAt()))
                .toList();
        int deleted = messageRepository.deleteByIdsAndOwner(
                toRemove.stream().map(ConversationMessageRepository.MessageRow::id).toList(),
                conversationId, userId);
        // WIN-39 (G1): 撤回消息 → 引用素材 ref_count -1（尽力而为，A7）
        for (ConversationMessageRepository.MessageRow removed : toRemove) {
            List<String> assetIds = parseJsonArray(removed.imageIds());
            if (!assetIds.isEmpty()) {
                assetService.adjustRefCounts(userId, assetIds, -1);
            }
        }
        log.info("[conversation] 撤回消息: user={}, conversation={}, message={}, removed={}",
                userId, conversationId, messageId, deleted);
        return deleted;
    }

    /** 批量删除指定消息（前端重试/重编辑流程移除失败消息，F1 写点）。 */
    public int deleteMessages(UUID userId, String conversationId, List<String> ids) {
        getOwned(userId, conversationId);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int deleted = messageRepository.deleteByIdsAndOwner(
                ids.stream().distinct().limit(200).toList(), conversationId, userId);
        log.info("[conversation] 批量删除消息: user={}, conversation={}, removed={}",
                userId, conversationId, deleted);
        // WIN-39 (G1 修复): 删除消息 → 引用素材 ref_count -1（尽力而为，A7）
        for (String id : ids) {
            messageRepository.findByIdAndOwner(id, conversationId, userId)
                    .ifPresent(row -> {
                        List<String> assetIds = parseJsonArray(row.imageIds());
                        if (!assetIds.isEmpty()) {
                            assetService.adjustRefCounts(userId, assetIds, -1);
                        }
                    });
        }
        return deleted;
    }

    // ===== image directory（assets source_kind='conversation'）=====

    /** 会话图片上传（multipart → assets，source_ref=conversation_id，ADR-36）。 */
    public AssetRepository.AssetRow uploadImage(UUID userId, String conversationId,
                                                byte[] bytes, String mimeType, String catalogSource,
                                                String description, Integer width, Integer height) {
        getOwned(userId, conversationId);
        String extra = "{}";
        try {
            Map<String, Object> extraMap = new LinkedHashMap<>();
            if (catalogSource != null && !catalogSource.isBlank()) {
                extraMap.put("catalogSource", catalogSource);
            }
            if (description != null && !description.isBlank()) {
                extraMap.put("description", description);
            }
            if (!extraMap.isEmpty()) {
                extra = objectMapper.writeValueAsString(extraMap);
            }
        } catch (Exception ignored) {
            // extra 非关键，降级 '{}'
        }
        return assetService.createImage(userId, null, null, List.of(), null,
                SOURCE_KIND_CONVERSATION, "会话图片", conversationId, null,
                bytes, mimeType, width, height, Instant.now(), extra);
    }

    /** 会话图片目录列表。 */
    public List<AssetRepository.AssetRow> listImages(UUID userId, String conversationId) {
        getOwned(userId, conversationId);
        return assetService.listConversationImages(userId, conversationId);
    }

    /** 图片访问（对象字节，属主校验）。 */
    public AssetService.StoredAssetFile getImage(UUID userId, String assetId) {
        return assetService.getFile(userId, assetId)
                .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "图片不存在"));
    }

    /** 更新会话图片登记描述（extra.description，ADR-36 目录语义）。 */
    public AssetRepository.AssetRow updateImageDescription(UUID userId, String assetId, String description) {
        AssetService.StoredAssetFile stored = assetService.getFile(userId, assetId)
                .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "图片不存在"));
        AssetRepository.AssetRow row = assetService.updateExtra(userId, assetId, Map.of("description",
                description == null ? "" : description));
        return row;
    }

    // ===== helpers =====

    public ConversationRepository.ConversationRow getOwned(UUID userId, String conversationId) {
        return repository.findByIdAndOwner(conversationId, userId)
                .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "会话不存在"));
    }

    private Instant parseCursor(String before) {
        if (before == null || before.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(before);
        } catch (Exception e) {
            throw new IllegalArgumentException("游标格式无效（应为 ISO 时间戳）");
        }
    }

    private String truncateTitle(String text) {
        String trimmed = text.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= 24) {
            return trimmed;
        }
        return trimmed.substring(0, 24) + "…";
    }

    /** pending 校验：JSONB 对象且 kind ∈ proposal|generation（FR-1.2）。 */
    private String validatePending(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("pending 必须是 JSON 对象");
        }
        JsonNode kind = node.get("kind");
        if (kind == null || !kind.isTextual()
                || !List.of("proposal", "generation").contains(kind.asText())) {
            throw new IllegalArgumentException("pending.kind 必须为 proposal 或 generation");
        }
        return node.toString();
    }

    private String validateJsonOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject() && !node.isArray()) {
            throw new IllegalArgumentException("字段必须是 JSON 对象或数组");
        }
        return node.toString();
    }

    private String jsonArrayOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("imageIds 必须是数组");
        }
        return node.toString();
    }

    private String parseExtraField(String extra, String field, String fallback) {
        if (extra == null || extra.isBlank()) {
            return fallback;
        }
        try {
            JsonNode node = objectMapper.readTree(extra);
            if (node != null && node.hasNonNull(field)) {
                return node.get(field).asText();
            }
        } catch (Exception ignored) {
            // 忽略解析失败
        }
        return fallback;
    }

    /** Public JSON shape for a conversation row. */
    public Map<String, Object> toJson(ConversationRepository.ConversationRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.id());
        map.put("title", row.title());
        map.put("status", row.status());
        map.put("imageModel", row.imageModel());
        map.put("webSearch", Boolean.TRUE.equals(row.webSearch()));
        map.put("pending", parseJsonObject(row.pending()));
        map.put("contextSummary", parseJsonObject(row.contextSummary()));
        map.put("deletedAt", row.deletedAt() == null ? null : row.deletedAt().toString());
        map.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        map.put("updatedAt", row.updatedAt() == null ? null : row.updatedAt().toString());
        map.put("lastMessageAt", row.lastMessageAt() == null ? null : row.lastMessageAt().toString());
        return map;
    }

    /** Public JSON shape for a message row. */
    public Map<String, Object> messageToJson(ConversationMessageRepository.MessageRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.id());
        map.put("conversationId", row.conversationId());
        map.put("role", row.role());
        map.put("text", row.text());
        map.put("reasoning", row.reasoning());
        map.put("imageIds", parseJsonArray(row.imageIds()));
        map.put("taskId", row.taskId());
        map.put("proposalData", parseJsonObject(row.proposalData()));
        map.put("webSearchUsed", row.webSearchUsed());
        map.put("withdrawable", Boolean.TRUE.equals(row.withdrawable()));
        map.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        return map;
    }

    private Object parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> parseJsonArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            List<String> result = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(item -> result.add(item.asText()));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }
}
