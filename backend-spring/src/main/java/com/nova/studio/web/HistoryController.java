package com.nova.studio.web;

import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.history.HistoryRepository;
import com.nova.studio.history.HistoryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WIN-39 (WIN-41 T12/T13, ARCH Part G.2) — 统一历史 API（histories 表）：
 * <ul>
 *   <li>{@code GET/DELETE /api/nova/histories} — 统一历史列表（type=reverse|gif，分页）/ 删除；</li>
 *   <li>{@code GET /api/nova/histories/images/{assetId}} — 历史图片访问（assets，属主校验）；</li>
 *   <li>反推：{@code POST /api/nova/reverse/records}（completed）、{@code GET ...?limit=2}（双槽）、
 *       {@code PUT /api/nova/reverse/draft}（草稿 upsert/清除）；</li>
 *   <li>GIF：{@code POST /api/nova/gif/jobs}（idle）、{@code GET/PATCH .../jobs/{id}}（状态查询/变迁）、
 *       {@code POST .../jobs/{id}/result}（成品上传）。</li>
 * </ul>
 * requireAuth + 属主隔离（AC-10）；非法状态迁移 → 409（ADR-39 状态机）。
 */
@RestController
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    // ===== 统一历史列表 / 详情 / 删除（C5/C6）=====

    @GetMapping("/api/nova/histories")
    public Map<String, Object> list(@RequestParam String type,
                                    @RequestParam(required = false) String before,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        HistoryRepository.HistoryPage page = historyService.listHistories(authUser.id(), type, before, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (HistoryRepository.HistoryRow row : page.items()) {
            items.add(historyService.toJson(row));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextBefore", page.nextBefore());
        return body;
    }

    @GetMapping("/api/nova/histories/{id}")
    public Map<String, Object> detail(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return historyService.toJson(historyService.getHistory(authUser.id(), id));
    }

    @DeleteMapping("/api/nova/histories/{id}")
    public Map<String, Object> delete(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        historyService.deleteHistory(authUser.id(), id);
        return Map.of("ok", true);
    }

    @GetMapping("/api/nova/histories/images/{assetId}")
    public ResponseEntity<?> image(@PathVariable String assetId, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        var stored = historyService.getImage(authUser.id(), assetId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(stored.data());
    }

    // ===== 反推（T12，映射 histories type=reverse）=====

    @PostMapping("/api/nova/reverse/records")
    public Map<String, Object> saveReverseRecord(@RequestBody JsonNode body,
                                                 @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return historyService.toJson(historyService.saveReverseRecord(authUser.id(), body));
    }

    @GetMapping("/api/nova/reverse/records")
    public Map<String, Object> listReverseRecords(@RequestParam(defaultValue = "2") int limit,
                                                  @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        List<Map<String, Object>> items = new ArrayList<>();
        for (HistoryRepository.HistoryRow row : historyService.listReverseRecords(authUser.id(), limit)) {
            items.add(historyService.toJson(row));
        }
        return Map.of("items", items);
    }

    @PutMapping("/api/nova/reverse/draft")
    public Map<String, Object> saveReverseDraft(@RequestBody(required = false) JsonNode body,
                                                @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        HistoryRepository.HistoryRow row = historyService.saveReverseDraft(authUser.id(), body);
        if (row == null) {
            return Map.of("ok", true, "cleared", true);
        }
        return historyService.toJson(row);
    }

    @GetMapping("/api/nova/reverse/draft")
    public Map<String, Object> getReverseDraft(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        HistoryRepository.HistoryRow row = historyService.getReverseDraft(authUser.id());
        if (row == null) {
            return Map.of("draft", (Object) null);
        }
        return Map.of("draft", historyService.toJson(row));
    }

    // ===== GIF（T13，映射 histories type=gif）=====

    @PostMapping("/api/nova/gif/jobs")
    public Map<String, Object> createGifJob(@RequestBody JsonNode body,
                                            @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return historyService.toJson(historyService.createGifJob(authUser.id(), body));
    }

    @GetMapping("/api/nova/gif/jobs/{id}")
    public Map<String, Object> getGifJob(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return historyService.toJson(historyService.getGifJob(authUser.id(), id));
    }

    @PatchMapping("/api/nova/gif/jobs/{id}")
    public Map<String, Object> patchGifJob(@PathVariable String id, @RequestBody JsonNode body,
                                           @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return historyService.toJson(historyService.patchGifJob(authUser.id(), id, body));
    }

    @PostMapping("/api/nova/gif/jobs/{id}/result")
    public Map<String, Object> uploadGifResult(@PathVariable String id,
                                               @RequestParam(required = false) MultipartFile file,
                                               @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请提供 GIF 文件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("文件读取失败");
        }
        return historyService.toJson(historyService.uploadGifResult(
                authUser.id(), id, bytes, file.getContentType()));
    }
}
