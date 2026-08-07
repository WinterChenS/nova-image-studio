package com.nova.studio.canvas;

import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WIN-40 T5 — 画布项目服务：CRUD、整文档 PUT（version 自增，A8 last-write-wins）、
 * 软删/恢复（C8）、配额校验（AC-11）、属主隔离 404（AC-10）、图片上传走 assets（ADR-35）。
 */
class CanvasServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private CanvasProjectRepository repository;
    private AssetService assetService;
    private SettingsService settingsService;
    private CanvasService service;

    private CanvasProjectRepository.CanvasRow row(String id, Long version) {
        return new CanvasProjectRepository.CanvasRow(id, USER_ID.toString(), "画布", "[]", "[]",
                "lines", false, "{\"x\":0,\"y\":0,\"k\":1}", version, null,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"));
    }

    @BeforeEach
    void setUp() {
        repository = mock(CanvasProjectRepository.class);
        assetService = mock(AssetService.class);
        settingsService = mock(SettingsService.class);
        service = new CanvasService(repository, assetService, settingsService, new ObjectMapper());
        when(settingsService.getInt(eq(USER_ID), eq("limit.canvasProjectCap"), eq(100))).thenReturn(100);
    }

    @Test
    void createStoresProjectWithDefaults() {
        when(repository.countActive(USER_ID)).thenReturn(0L);
        when(repository.findByIdAndOwner(anyString(), eq(USER_ID))).thenAnswer(invocation ->
                Optional.of(row(invocation.getArgument(0), 1L)));
        CanvasProjectRepository.CanvasRow created = service.create(USER_ID, null);
        assertThat(created.title()).isEqualTo("画布");
        verify(repository).insert(any(CanvasProjectEntity.class));
    }

    @Test
    void createRejectsWhenQuotaExceeded() {
        when(repository.countActive(USER_ID)).thenReturn(100L);
        assertThatThrownBy(() -> service.create(USER_ID, null))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("QUOTA_EXCEEDED");
                });
        verify(repository, never()).insert(any(CanvasProjectEntity.class));
    }

    @Test
    void saveDocumentIncrementsVersion() {
        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.of(row("c1", 3L)));
        ObjectMapper mapper = new ObjectMapper();
        var body = mapper.createObjectNode();
        body.putArray("nodes").addObject().put("id", "n1");
        body.putArray("connections").addObject().put("id", "n2");
        service.saveDocument(USER_ID, "c1", body);

        verify(repository).saveDocument(org.mockito.ArgumentMatchers.argThat(patch ->
                patch.getVersion() != null && patch.getVersion() == 4L));
    }

    @Test
    void getReturns404ForCrossUser() {
        when(repository.findByIdAndOwner("c1", OTHER_USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOwned(OTHER_USER, "c1"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    void softDeleteThenRestore() {
        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.of(row("c1", 1L)));
        service.softDelete(USER_ID, "c1");
        verify(repository).softDelete(eq("c1"), any(Instant.class));

        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.of(
                new CanvasProjectRepository.CanvasRow("c1", USER_ID.toString(), "画布", "[]", "[]",
                        "lines", false, "{\"x\":0,\"y\":0,\"k\":1}", 1L,
                        Instant.parse("2026-07-01T00:00:00Z"),
                        Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"))));
        service.restore(USER_ID, "c1");
        verify(repository).restore("c1");
    }

    @Test
    void uploadImageDelegatesToAssetsWithCanvasSource() {
        when(assetService.createImage(eq(USER_ID), any(), any(), any(), any(), eq("canvas"),
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AssetRepository.AssetRow("a1", USER_ID.toString(), "p1", "image", "画布图",
                        "image/png", 10L, 100, 200, "[]", null, "canvas", "画布图片", null,
                        null, "assets/u1/a1.png", "abc", "{}", null, 0L,
                        Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"), null));
        AssetRepository.AssetRow created = service.uploadImage(USER_ID, new byte[]{1}, "image/png",
                "画布图", 100, 200);
        assertThat(created.sourceKind()).isEqualTo("canvas");
    }

    @Test
    void saveDocumentRejectsInvalidNodes() {
        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.of(row("c1", 1L)));
        ObjectMapper mapper = new ObjectMapper();
        assertThatThrownBy(() -> service.saveDocument(USER_ID, "c1",
                mapper.createObjectNode().put("nodes", "not-json-array")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodes");
    }
}
