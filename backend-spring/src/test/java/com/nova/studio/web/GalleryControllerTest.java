package com.nova.studio.web;

import com.nova.studio.auth.AuthUser;
import com.nova.studio.gallery.GalleryDataService;
import com.nova.studio.infra.RuntimeEnv;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checklist #12 — gallery/config endpoints (Node parity): prompts/blacklist
 * served from the DB via {@link GalleryDataService} (public payload shape
 * {@code [{title, content, type}]} / {@code {keywords: [...]}} unchanged),
 * config mode + password flag, verify sha256(password) check and the
 * no-password passthrough. T3.1: file reads moved into GalleryDataService.
 */
class GalleryControllerTest {

    private final RuntimeEnv runtimeEnv = mock(RuntimeEnv.class);
    private final GalleryDataService galleryDataService = mock(GalleryDataService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private GalleryController newController() {
        return new GalleryController(runtimeEnv, galleryDataService);
    }

    @Test
    void promptsServedFromDbWithPublicShape() {
        UUID id = UUID.randomUUID();
        when(galleryDataService.prompts()).thenReturn(List.of(
                new GalleryDataService.PromptRow(id, "去水印", "去除画面中的水印", 2, true, 0)));
        List<Map<String, Object>> body = newController().prompts();
        assertThat(body).hasSize(1);
        assertThat(body.get(0))
                .containsEntry("title", "去水印")
                .containsEntry("content", "去除画面中的水印")
                .containsEntry("type", 2);
    }

    @Test
    void blacklistKeywordsReturnedFromDb() {
        when(galleryDataService.blacklistKeywords()).thenReturn(List.of("色情", "暴力"));
        assertThat(newController().blacklist()).isEqualTo(Map.of("keywords", List.of("色情", "暴力")));
    }

    @Test
    void promptsFilterHiddenRowsOnPublicEndpoint() {
        // T3.1 复审闭环：admin 设 enabled=false 的条目对公开端点隐藏（admin 列表仍全量）。
        when(galleryDataService.prompts()).thenReturn(List.of(
                new GalleryDataService.PromptRow(UUID.randomUUID(), "可见", "c", 1, true, 0),
                new GalleryDataService.PromptRow(UUID.randomUUID(), "隐藏", "c", 1, false, 1)));
        List<Map<String, Object>> body = newController().prompts();
        assertThat(body).extracting(item -> item.get("title")).containsExactly("可见");
    }

    @Test
    void configModeAndPasswordEnabled() {
        when(runtimeEnv.getString("PROMPT_GALLERY_MODE", "2")).thenReturn("1");
        when(runtimeEnv.getString("PROMPT_GALLERY_PASSWORD", "")).thenReturn("8848");
        var body = newController().config().getBody();
        assertThat(body).containsEntry("promptGalleryMode", "1")
                .containsEntry("promptGalleryPasswordEnabled", true);
    }

    @Test
    void verifyChecksHashedPassword() {
        when(runtimeEnv.getString("PROMPT_GALLERY_PASSWORD", "")).thenReturn("8848");
        assertThat(newController().verify(mapper.createObjectNode().put("password", "8848")))
                .isEqualTo(Map.of("ok", true));
        assertThat(newController().verify(mapper.createObjectNode().put("password", "wrong")))
                .isEqualTo(Map.of("ok", false));
    }

    @Test
    void verifyWithoutConfiguredPasswordAlwaysOk() {
        when(runtimeEnv.getString("PROMPT_GALLERY_PASSWORD", "")).thenReturn("");
        assertThat(newController().verify(mapper.createObjectNode().put("password", "anything")))
                .isEqualTo(Map.of("ok", true));
    }
}
