package com.nova.studio.web;

import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.conversation.ConversationRepository;
import com.nova.studio.conversation.ConversationMessageRepository;
import com.nova.studio.conversation.ConversationService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WIN-39 (WIN-40 T3) — Agent 会话持久化 API（ARCH Part G.1 阶段1 子集）：
 * <ul>
 *   <li>{@code GET/POST /api/nova/agent/conversations} — 会话列表 / 新建；</li>
 *   <li>{@code GET/PATCH /api/nova/agent/conversations/{id}} — 详情（pending/context_summary/图片目录）/ 字段更新；</li>
 *   <li>{@code DELETE .../restore} — 软删进回收站 / 恢复（C8）；</li>
 *   <li>{@code GET/POST .../messages} — 消息分页（before 游标）/ 单条追加（F1 写点）；</li>
 *   <li>{@code POST .../messages/{mid}/withdraw} — 撤回；</li>
 *   <li>{@code POST /api/nova/agent/images} + {@code GET .../images/{assetId}} — 会话图片目录（assets）。</li>
 * </ul>
 * 每条路由 requireAuth；属主隔离 → 404（AC-10）；配额超限 → 409（AC-11）。
 */
@RestController
@RequestMapping("/api/nova/agent")
public class AgentConversationController {

    private final ConversationService conversationService;
    private final AssetService assetService;

    public AgentConversationController(ConversationService conversationService, AssetService assetService) {
        this.conversationService = conversationService;
        this.assetService = assetService;
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
     * 单条消息追加（阶段1 纯存储写点；阶段2 T9 升级为 SSE 统一事件流，接口路径不变）。
     */
    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<Map<String, Object>> appendMessage(@PathVariable String id,
                                                             @RequestBody JsonNode body,
                                                             @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        ConversationMessageRepository.MessageRow created =
                conversationService.appendMessage(authUser.id(), id, body);
        return ResponseEntity.status(201).body(conversationService.messageToJson(created));
    }

    @PostMapping("/conversations/{id}/messages/{mid}/withdraw")
    public Map<String, Object> withdraw(@PathVariable String id, @PathVariable String mid,
                                        @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        int removed = conversationService.withdraw(authUser.id(), id, mid);
        return Map.of("ok", true, "removed", removed);
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

    private Map<String, Object> assetJson(AssetRepository.AssetRow row) {
        Map<String, Object> map = new LinkedHashMap<>(AssetService.toJson(row));
        map.put("assetId", row.id());
        return map;
    }
}
