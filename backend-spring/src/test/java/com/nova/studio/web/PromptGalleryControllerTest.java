package com.nova.studio.web;

import com.nova.studio.auth.AuthUser;
import com.nova.studio.gallery.PromptGalleryItemEntity;
import com.nova.studio.gallery.PromptGalleryItemRepository;
import com.nova.studio.gallery.PromptGallerySyncService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.SettingsService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WIN-42 (T15/T16, ADR-40) — 提示广场读库 API 与管理端点单测：
 * 读库返回 {items,total,page,pageSize,categories,sources}（AC-9），
 * 手动同步走 admin 端点 + 限频（A5），同步状态可查（AC-12）。
 */
class PromptGalleryControllerTest {

    private final PromptGalleryItemRepository repository = mock(PromptGalleryItemRepository.class);
    private final PromptGallerySyncService syncService = mock(PromptGallerySyncService.class);
    private final SettingsService settingsService = mock(SettingsService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final AuthUser ADMIN = new AuthUser(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "admin", "admin");
    private static final AuthUser USER = new AuthUser(
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "user", "user");

    private PromptGalleryController newPublicController() {
        return new PromptGalleryController(repository, objectMapper);
    }

    private PromptGalleryAdminController newAdminController() {
        return new PromptGalleryAdminController(syncService, settingsService, objectMapper);
    }

    private PromptGalleryItemEntity entity() {
        PromptGalleryItemEntity e = new PromptGalleryItemEntity();
        e.setId("nanobanana-sec1-p1-0-0");
        e.setSource("nanobanana");
        e.setSourceUrl("https://github.com/x/y");
        e.setTitle("海报示例");
        e.setContent("一张海报");
        e.setImages("[\"https://img.example/a.png\"]");
        e.setTags("[\"海报\"]");
        e.setCategory("海报");
        e.setContributor("作者");
        e.setNotes("");
        e.setSyncedAt(Instant.parse("2026-08-16T00:00:00Z"));
        return e;
    }

    @Test
    void itemsReturnsPagedReadShape() {
        when(repository.search(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PromptGalleryItemRepository.GalleryPage(List.of(entity()), 1L, 1, 20));
        when(repository.distinctCategories()).thenReturn(List.of("海报"));
        when(repository.distinctSources()).thenReturn(List.of("nanobanana"));
        Map<String, Object> body = newPublicController().items(null, null, null, null, 1, 20);
        assertThat(body).containsKeys("items", "total", "page", "pageSize", "categories", "sources");
        assertThat(body.get("total")).isEqualTo(1L);
        assertThat(body.get("categories")).isEqualTo(List.of("海报"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = objectMapper.convertValue(body.get("items"), List.class);
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("uniqueKey", "nanobanana-sec1-p1-0-0")
                .containsEntry("category", "海报")
                .containsKey("images");
    }

    @Test
    void itemNotFoundReturns404() {
        when(repository.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> newPublicController().item("missing"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    void manualSyncAdminRejectsNonAdmin() {
        assertThatThrownBy(() -> newAdminController().manualSync(USER))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(403);
                    assertThat(e.getCode()).isEqualTo("FORBIDDEN");
                });
    }

    @Test
    void manualSyncReadsCooldownSettingAndDelegates() {
        when(settingsService.getInt(ADMIN.id(),
                SettingsService.KEY_GALLERY_SYNC_MANUAL_COOLDOWN_MINUTES,
                SettingsService.DEFAULT_GALLERY_SYNC_MANUAL_COOLDOWN_MINUTES)).thenReturn(5);
        when(syncService.manualSync(5)).thenReturn(new PromptGallerySyncService.SyncResult(
                "succeeded", Instant.now(), Instant.now(), 42,
                List.of(new PromptGallerySyncService.SourceResult("nanobanana", "succeeded", 42, null)), null));
        Map<String, Object> body = newAdminController().manualSync(ADMIN);
        assertThat(body).containsEntry("status", "succeeded").containsEntry("totalUpserted", 42);
        verify(syncService).manualSync(5);
    }

    @Test
    void syncStatusQueryable() {
        tools.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
        node.put("status", "succeeded");
        when(syncService.status()).thenReturn(node);
        Map<String, Object> body = newAdminController().syncStatus(ADMIN);
        assertThat(body).containsEntry("status", "succeeded");
    }
}
