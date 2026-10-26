package com.nova.studio.web;

import com.nova.studio.accountpool.AccountScheduler;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.agent.AgentChatService;
import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.conversation.ConversationRepository;
import com.nova.studio.conversation.ConversationMessageRepository;
import com.nova.studio.conversation.ConversationService;
import com.nova.studio.textproxy.TextProxyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WIN-39 (WIN-40 T3 + WIN-41 T9/T10) — Agent 会话持久化 + 后端化托管 API（ARCH Part G.1）：
 * <ul>
 *   <li>{@code GET/POST /api/nova/agent/conversations} — 会话列表 / 新建；</li>
 *   <li>{@code GET/PATCH /api/nova/agent/conversations/{id}} — 详情（pending/context_summary/图片目录）/ 字段更新；</li>
 *   <li>{@code DELETE .../restore} — 软删进回收站 / 恢复（C8）；</li>
 *   <li>{@code GET .../messages} — 消息分页（before 游标）；</li>
 *   <li>{@code POST .../messages} — {@code Accept: text/event-stream} → SSE 统一事件流（T9 后端化托管，
 *       会话/历史/图片目录/指令/工具 schema 由后端组装）；{@code X-Agent-Stream: raw} → 原始透传回退（对拍/应急，G.3）；
 *       否则 JSON 单条追加（system-note/context-divider 等存储写点）；</li>
 *   <li>{@code POST .../messages/{mid}/withdraw} — 撤回；</li>
 *   <li>{@code POST /api/nova/agent/describe} — 图片描述（vision，T9 替代前端 describing 直连）；</li>
 *   <li>{@code POST /api/nova/agent/images} + {@code GET .../images/{assetId}} — 会话图片目录（assets）。</li>
 * </ul>
 * 每条路由 requireAuth；属主隔离 → 404（AC-10）；配额超限 → 409（AC-11）；同会话并发流 → 409 CONCURRENT_STREAM。
 */
@RestController
@RequestMapping("/api/nova/agent")
public class AgentConversationController {

    private final ConversationService conversationService;
    private final AssetService assetService;
    private final AgentChatService agentChatService;
    private final TextProxyService textProxyService;
    private final ObjectMapper objectMapper;

    public AgentConversationController(ConversationService conversationService,
                                       AssetService assetService,
                                       AgentChatService agentChatService,
                                       TextProxyService textProxyService,
                                       ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.assetService = assetService;
        this.agentChatService = agentChatService;
        this.textProxyService = textProxyService;
        this.objectMapper = objectMapper;
    }

    // ===== conversations =====

    @GetMapping("/conversations")
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String before,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        ConversationRepository.ConversationPage page =
                conversationService.list(authUser.id(), status, before, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ConversationRepository.ConversationRow row : page.items()) {
            items.add(conversationService.toJson(row));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextBefore", page.nextBefore());
        return body;
    }

    @PostMapping("/conversations")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) JsonNode body,
                                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        String title = body != null && body.hasNonNull("title") ? body.get("title").asText() : null;
        ConversationRepository.ConversationRow created = conversationService.create(authUser.id(), title);
        return ResponseEntity.status(201).body(conversationService.toJson(created));
    }

    @GetMapping("/conversations/{id}")
    public Map<String, Object> detail(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return conversationService.detail(authUser.id(), id);
    }

    @PatchMapping("/conversations/{id}")
    public Map<String, Object> patch(@PathVariable String id, @RequestBody JsonNode body,
                                     @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return conversationService.toJson(conversationService.patch(authUser.id(), id, body));
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> softDelete(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        conversationService.softDelete(authUser.id(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/conversations/{id}/restore")
    public Map<String, Object> restore(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        conversationService.restore(authUser.id(), id);
        return Map.of("ok", true);
    }

    /** T16：清空回收站（硬删全部 status=deleted 会话 + 级联消息/素材）。 */
    @DeleteMapping("/conversations/trash")
    public Map<String, Object> emptyTrash(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        int removed = conversationService.emptyTrash(authUser.id());
        return Map.of("ok", true, "removed", removed);
    }

    // ===== messages =====

    @GetMapping("/conversations/{id}/messages")
    public Map<String, Object> listMessages(@PathVariable String id,
                                            @RequestParam(required = false) String before,
                                            @RequestParam(defaultValue = "50") int limit,
                                            @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        ConversationMessageRepository.MessagePage page =
                conversationService.listMessages(authUser.id(), id, before, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ConversationMessageRepository.MessageRow row : page.items()) {
            items.add(conversationService.messageToJson(row));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextBefore", page.nextBefore());
        return body;
    }

    /**
     * 消息发送（T9 后端化托管）：
     * <ul>
     *   <li>{@code Accept: text/event-stream} → SSE 统一事件流（会话托管，前端不传协议/完整历史）；</li>
     *   <li>{@code X-Agent-Stream: raw} → 原始透传回退（G.3 回退开关：请求体含 requestBody，后端仅解析
     *       账号/模型后逐字节透传上游 SSE，供对拍/应急）；</li>
     *   <li>否则 JSON 单条追加（system-note/context-divider 等存储写点，F1）。</li>
     * </ul>
     */
    @PostMapping("/conversations/{id}/messages")
    public void sendMessage(@PathVariable String id,
                            @RequestBody(required = false) JsonNode body,
                            @RequestHeader(value = "Accept", required = false) String accept,
                            @RequestHeader(value = "X-Agent-Stream", required = false) String agentStream,
                            @AuthenticationPrincipal AuthUser authUser,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        AuthUser user = AuthSupport.requireAuth(authUser);
        boolean wantsSse = accept != null && accept.toLowerCase().contains("text/event-stream");
        boolean raw = "raw".equalsIgnoreCase(agentStream);
        if (!wantsSse) {
            // JSON 单条追加（阶段1 语义不变）
            ConversationMessageRepository.MessageRow created =
                    conversationService.appendMessage(user.id(), id, body);
            writeJson(response, 201, conversationService.messageToJson(created));
            return;
        }
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");
        if (raw) {
            rawPassthrough(user, id, body, response);
            return;
        }
        AgentChatService.SseWriter writer = (type, data) -> {
            response.getWriter().write("event: " + type + "\n");
            response.getWriter().write("data: " + objectMapper.writeValueAsString(data) + "\n\n");
            response.getWriter().flush();
        };
        agentChatService.streamChat(user.id(), id, body, writer);
    }

    /**
     * 原始透传回退（G.3）：请求体 {model（目录文本模型 UUID）, requestBody} → 解析账号/模型后
     * 逐字节透传上游 SSE（无转译；前端回退模式仍用旧 sse-stream-parser 解析）。
     */
    private void rawPassthrough(AuthUser user, String conversationId, JsonNode body,
                                HttpServletResponse response) throws IOException {
        String modelField = body == null ? null : text(body, "model");
        if (modelField == null) {
            writeJson(response, 400, Map.of("error", "Missing model"));
            return;
        }
        CatalogModelRepository.Row model = agentChatService.resolveTextModelPublic(modelField);
        if (model == null) {
            writeJson(response, 400, Map.of("error", "未找到文本模型配置"));
            return;
        }
        JsonNode requestBody = body != null && body.has("requestBody") ? body.get("requestBody") : body;
        try {
            var selected = agentChatService.selectAccount(model, Set.of());
            try {
                TextProxyService.Target target = textProxyService.buildTarget(
                        selected.protocol(), selected.baseUrl(), selected.apiKey(), model.modelId(), true);
                TextProxyService.ProxyExchange exchange = textProxyService.exchange(target, requestBody);
                if (exchange.streamed()) {
                    exchange.transferTo(response.getOutputStream());
                } else {
                    writeJson(response, exchange.status(),
                            exchange.jsonBody() == null ? Map.of("error", "上游返回 " + exchange.status())
                                    : parseJsonOrRaw(exchange.jsonBody()));
                }
            } finally {
                agentChatService.releaseAccount(selected.accountId());
            }
        } catch (Exception e) {
            writeJson(response, 502, Map.of("error", "代理请求失败：" + e.getMessage()));
        }
    }

    /**
     * 图片描述（T9，vision）：body {assetId, model（目录文本模型 UUID）} → 复用账号池非流式转发。
     */
    @PostMapping("/describe")
    public Map<String, Object> describe(@RequestBody JsonNode body, @AuthenticationPrincipal AuthUser authUser)
            throws IOException {
        AuthSupport.requireAuth(authUser);
        if (body == null || !body.hasNonNull("assetId")) {
            throw new IllegalArgumentException("缺少 assetId");
        }
        String model = body.hasNonNull("model") ? body.get("model").asText() : null;
        String description = agentChatService.describeImage(authUser.id(), body.get("assetId").asText(), model);
        return Map.of("description", description);
    }

    @PostMapping("/conversations/{id}/messages/{mid}/withdraw")
    public Map<String, Object> withdraw(@PathVariable String id, @PathVariable String mid,
                                        @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        int removed = conversationService.withdraw(authUser.id(), id, mid);
        return Map.of("ok", true, "removed", removed);
    }

    /** 批量删除指定消息（前端重试/重编辑流程，F1 写点）。 */
    @PostMapping("/conversations/{id}/messages/batch-delete")
    public Map<String, Object> batchDeleteMessages(@PathVariable String id, @RequestBody JsonNode body,
                                                   @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        List<String> ids = new ArrayList<>();
        if (body != null && body.has("ids") && body.get("ids").isArray()) {
            body.get("ids").forEach(node -> ids.add(node.asText()));
        }
        int deleted = conversationService.deleteMessages(authUser.id(), id, ids);
        return Map.of("ok", true, "deleted", deleted);
    }

    // ===== image directory（assets）=====

    @PostMapping("/images")
    public ResponseEntity<Map<String, Object>> uploadImage(
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Integer width,
            @RequestParam(required = false) Integer height,
            @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请提供图片文件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("文件读取失败");
        }
        AssetRepository.AssetRow created = conversationService.uploadImage(
                authUser.id(), conversationId, bytes, file.getContentType(),
                source, description, width, height);
        return ResponseEntity.status(201).body(assetJson(created));
    }

    @GetMapping("/conversations/{id}/images")
    public Map<String, Object> listImages(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AssetRepository.AssetRow row : conversationService.listImages(authUser.id(), id)) {
            items.add(assetJson(row));
        }
        return Map.of("items", items);
    }

    @GetMapping("/images/{assetId}")
    public ResponseEntity<?> getImage(@PathVariable String assetId, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        AssetService.StoredAssetFile stored = conversationService.getImage(authUser.id(), assetId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(stored.data());
    }

    /** 更新会话图片登记元数据（extra.description，ADR-36 目录语义保留）。 */
    @PatchMapping("/images/{assetId}")
    public Map<String, Object> updateImageDescription(@PathVariable String assetId, @RequestBody JsonNode body,
                                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        if (body == null || !body.has("description")) {
            throw new IllegalArgumentException("缺少 description");
        }
        AssetRepository.AssetRow updated = conversationService.updateImageDescription(
                authUser.id(), assetId, body.get("description").asText());
        return assetJson(updated);
    }

    private Map<String, Object> assetJson(AssetRepository.AssetRow row) {
        Map<String, Object> map = new LinkedHashMap<>(AssetService.toJson(row));
        map.put("assetId", row.id());
        return map;
    }

    private void writeJson(HttpServletResponse response, int status, Object payload) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(payload));
    }

    private Object parseJsonOrRaw(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) {
            return Map.of("error", "上游返回异常");
        }
        try {
            return objectMapper.readValue(bodyText, Object.class);
        } catch (Exception e) {
            return Map.of("error", "上游返回异常");
        }
    }

    private static String text(JsonNode body, String key) {
        if (body == null || !body.has(key) || !body.get(key).isTextual()) {
            return null;
        }
        return body.get(key).asText();
    }
}
