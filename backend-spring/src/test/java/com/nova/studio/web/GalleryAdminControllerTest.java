package com.nova.studio.web;

import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.gallery.GalleryDataService;
import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3.1 — admin management API authorization (H5: 管理接口 admin 角色) and CRUD
 * delegation to {@link GalleryDataService}: 401 anonymous / 403 non-admin /
 * admin passthrough for prompts and blacklist endpoints.
 */
class GalleryAdminControllerTest {

    private final GalleryDataService galleryDataService = mock(GalleryDataService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private GalleryAdminController controller;

    private final AuthUser admin = new AuthUser(UUID.randomUUID(), "boss", "admin");
    private final AuthUser user = new AuthUser(UUID.randomUUID(), "alice", "user");

    @BeforeEach
    void setUp() {
        controller = new GalleryAdminController(galleryDataService);
    }

    private ObjectNode body() {
        return mapper.createObjectNode();
    }

    // ===== authorization =====

    @Test
    void anonymousRejectedOnPromptsList() {
        assertThatThrownBy(() -> controller.listPrompts(null))
                .isInstanceOf(HttpErrorException.class)
                .satisfies(e -> assertThat(((HttpErrorException) e).getStatusCode()).isEqualTo(401));
    }

    @Test
    void nonAdminRejectedOnPromptsList() {
        assertThatThrownBy(() -> controller.listPrompts(user))
                .isInstanceOf(HttpErrorException.class)
                .satisfies(e -> assertThat(((HttpErrorException) e).getStatusCode()).isEqualTo(403));
    }

    @Test
    void anonymousRejectedOnBlacklistAdd() {
        ObjectNode json = body().put("keyword", "违禁词");
        assertThatThrownBy(() -> controller.addKeyword(json, null))
                .isInstanceOf(HttpErrorException.class)
                .satisfies(e -> assertThat(((HttpErrorException) e).getStatusCode()).isEqualTo(401));
        verify(galleryDataService, never()).addKeyword(any());
    }

    @Test
    void nonAdminRejectedOnPromptDelete() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> controller.deletePrompt(id, user))
                .isInstanceOf(HttpErrorException.class)
                .satisfies(e -> assertThat(((HttpErrorException) e).getStatusCode()).isEqualTo(403));
        verify(galleryDataService, never()).deletePrompt(any());
    }

    // ===== admin CRUD delegation =====

    @Test
    void adminListsPrompts() {
        GalleryDataService.PromptRow row =
                new GalleryDataService.PromptRow(UUID.randomUUID(), "标题", "内容", 1, true, 0);
        when(galleryDataService.listPrompts()).thenReturn(List.of(row));
        assertThat(controller.listPrompts(admin)).containsExactly(row);
    }

    @Test
    void adminCreatesPrompt() {
        ObjectNode json = body().put("title", "新提示").put("content", "内容").put("type", 2);
        GalleryDataService.PromptRow row =
                new GalleryDataService.PromptRow(UUID.randomUUID(), "新提示", "内容", 2, true, 0);
        when(galleryDataService.createPrompt(eq("新提示"), eq("内容"), eq(2), any(), any()))
                .thenReturn(row);
        var resp = controller.createPrompt(json, admin);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody()).isEqualTo(row);
    }

    @Test
    void adminUpdatesPromptPartial() {
        ObjectNode json = body().put("enabled", false);
        UUID id = UUID.randomUUID();
        GalleryDataService.PromptRow row =
                new GalleryDataService.PromptRow(id, "原标题", "内容", 1, false, 0);
        when(galleryDataService.updatePrompt(eq(id), any(), any(), any(), eq(false), any()))
                .thenReturn(row);
        assertThat(controller.updatePrompt(id, json, admin)).isEqualTo(row);
    }

    @Test
    void adminDeletesPrompt() {
        UUID id = UUID.randomUUID();
        assertThat(controller.deletePrompt(id, admin)).isEqualTo(java.util.Map.of("ok", true));
        verify(galleryDataService).deletePrompt(id);
    }

    @Test
    void adminAddsKeyword() {
        ObjectNode json = body().put("keyword", "色情");
        GalleryDataService.KeywordRow row = new GalleryDataService.KeywordRow(UUID.randomUUID(), "色情");
        when(galleryDataService.addKeyword("色情")).thenReturn(row);
        var resp = controller.addKeyword(json, admin);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody()).isEqualTo(row);
    }

    @Test
    void adminDeletesKeyword() {
        assertThat(controller.deleteKeyword("色情", admin)).isEqualTo(java.util.Map.of("ok", true));
        verify(galleryDataService).deleteKeyword("色情");
    }
}
