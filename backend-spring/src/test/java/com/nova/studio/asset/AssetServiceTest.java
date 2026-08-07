package com.nova.studio.asset;

import com.nova.studio.asset.AssetRepository.AssetRow;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.project.ProjectService;
import com.nova.studio.storage.ObjectStorageManager;
import com.nova.studio.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WIN-22 (F-10..F-21 / D.2 / ADR-16/17/18) — asset service: server-generated
 * object keys (D13 stable path), MIME/size guards, dedup 409 (R-5), default
 * project fallback, ownership 404, move (key unchanged) and DB+object delete.
 */
class AssetServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private AssetRepository repository;
    private ObjectStorageManager storageManager;
    private ObjectStorageService storage;
    private ProjectService projectService;
    private AssetService service;

    private AssetRow row(String id, String projectId, String kind, String storageKey) {
        return new AssetRow(id, USER_ID.toString(), projectId, kind, "name", "image/png", 10L, 1, 1,
                "[]", null, "upload", "用户上传", null, null, storageKey, "abc",
                "{}", null, 0L,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"), null);
    }

    @BeforeEach
    void setUp() {
        repository = mock(AssetRepository.class);
        storageManager = mock(ObjectStorageManager.class);
        storage = mock(ObjectStorageService.class);
        projectService = mock(ProjectService.class);
        service = new AssetService(repository, storageManager, projectService, 20 * 1024 * 1024);
        when(storageManager.active()).thenReturn(storage);
        when(projectService.defaultProjectId(USER_ID)).thenReturn("p-default");
    }

    @Test
    void createImageUsesStableKeyAndStoresBytes() {
        when(repository.findDuplicate(eq(USER_ID), eq("p-default"), anyString())).thenReturn(Optional.empty());
        when(repository.findByIdAndOwner(anyString(), eq(USER_ID))).thenAnswer(invocation ->
                Optional.of(row(invocation.getArgument(0), "p-default", "image",
                        "assets/" + USER_ID + "/" + invocation.getArgument(0) + ".png")));

        AssetRow created = service.createImage(USER_ID, null, "测试图", List.of("tag1"),
                null, "upload", null, null, null,
                new byte[]{1, 2, 3}, "image/png", 100, 200, Instant.now());

        assertThat(created.storageKey()).startsWith("assets/" + USER_ID + "/").endsWith(".png");
        verify(storage).put(created.storageKey(), new byte[]{1, 2, 3}, "image/png");
    }

    @Test
    void createImageRejectsNonImageMime() {
        assertThatThrownBy(() -> service.createImage(USER_ID, null, null, null,
                null, "upload", null, null, null, new byte[]{1}, "text/plain", null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持图片文件");
        verify(storage, never()).put(anyString(), any(), any());
    }

    @Test
    void createImageRejectsOversize() {
        assertThatThrownBy(() -> service.createImage(USER_ID, null, null, null,
                null, "upload", null, null, null, new byte[20 * 1024 * 1024 + 1], "image/png", null, null, Instant.now()))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(413);
                    assertThat(e.getCode()).isEqualTo("PAYLOAD_TOO_LARGE");
                });
    }

    @Test
    void createImageDuplicateInSameProjectYields409() {
        when(repository.findDuplicate(eq(USER_ID), eq("p1"), anyString())).thenReturn(Optional.of(
                row("existing", "p1", "image", "assets/" + USER_ID + "/existing.png")));
        when(projectService.owns(USER_ID, "p1")).thenReturn(true);
        assertThatThrownBy(() -> service.createImage(USER_ID, "p1", null, null,
                null, "upload", null, null, null, new byte[]{1}, "image/png", null, null, Instant.now()))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("ASSET_ALREADY_EXISTS");
                });
        verify(storage, never()).put(anyString(), any(), any());
    }

    @Test
    void createImageRejectsCrossUserProject() {
        when(projectService.owns(USER_ID, "p-other")).thenReturn(false);
        assertThatThrownBy(() -> service.createImage(USER_ID, "p-other", null, null,
                null, "upload", null, null, null, new byte[]{1}, "image/png", null, null, Instant.now()))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    void createTextStoresContentInPrompt() {
        when(repository.findDuplicate(eq(USER_ID), eq("p1"), anyString())).thenReturn(Optional.empty());
        when(projectService.owns(USER_ID, "p1")).thenReturn(true);
        when(repository.findByIdAndOwner(anyString(), eq(USER_ID))).thenReturn(Optional.of(
                row("a2", "p1", "text", null)));

        AssetRow created = service.createText(USER_ID, "p1", "一个可爱的猫", "提示词",
                List.of("提示词"), null, "manual", "迁移导入", null, Instant.now());

        assertThat(created.kind()).isEqualTo("text");
        verify(repository).insert(any(AssetEntity.class));
        verify(storage, never()).put(anyString(), any(), any());
    }

    @Test
    void getReturns404ForCrossUser() {
        when(repository.findByIdAndOwner("a1", USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(USER_ID, "a1"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    void updateMoveChangesProjectButKeepsKey() {
        when(repository.findByIdAndOwner("a1", USER_ID)).thenReturn(Optional.of(
                row("a1", "p1", "image", "assets/" + USER_ID + "/a1.png")));
        when(projectService.owns(USER_ID, "p2")).thenReturn(true);

        AssetRow updated = service.update(USER_ID, "a1", "新名字", List.of("x"), null, "p2");

        assertThat(updated.id()).isEqualTo("a1");
        verify(repository).update(any(AssetEntity.class));
    }

    @Test
    void deleteSoftDeletesIntoRecycleBin() {
        when(repository.findByIdAndOwner("a1", USER_ID)).thenReturn(Optional.of(
                row("a1", "p1", "image", "assets/" + USER_ID + "/a1.png")));
        service.delete(USER_ID, "a1");
        verify(repository).softDelete(eq("a1"), any(Instant.class));
        // WIN-39 软删语义：对象不立即删除（保留至清理任务）
        verify(storage, never()).delete(anyString());
    }

    @Test
    void deleteWithRefCountGreaterThanZeroForcesSoftDelete() {
        when(repository.findByIdAndOwner("a1", USER_ID)).thenReturn(Optional.of(
                new AssetRow("a1", USER_ID.toString(), "p1", "image", "name", "image/png", 10L, 1, 1,
                        "[]", null, "canvas", "画布", null, null, "assets/" + USER_ID + "/a1.png", "abc",
                        "{}", null, 3L,
                        Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"), null)));
        service.delete(USER_ID, "a1");
        verify(repository).softDelete(eq("a1"), any(Instant.class));
    }

    @Test
    void restoreClearsDeletedAt() {
        when(repository.findByIdAndOwner("a1", USER_ID)).thenReturn(Optional.of(
                new AssetRow("a1", USER_ID.toString(), "p1", "image", "name", "image/png", 10L, 1, 1,
                        "[]", null, "upload", null, null, null, "assets/" + USER_ID + "/a1.png", "abc",
                        "{}", Instant.parse("2026-07-01T00:00:00Z"), 0L,
                        Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"), null)));
        service.restore(USER_ID, "a1");
        verify(repository).restore("a1");
    }

    @Test
    void adjustRefCountsDelegatesToRepository() {
        service.adjustRefCounts(USER_ID, List.of("a1", "a2"), 1);
        verify(repository).adjustRefCounts(USER_ID, List.of("a1", "a2"), 1);
    }

    @Test
    void createImageAcceptsCanvasAndConversationSourceKinds() {
        for (String sourceKind : new String[]{"canvas", "conversation"}) {
            when(repository.findDuplicate(eq(USER_ID), eq("p-default"), anyString())).thenReturn(Optional.empty());
            when(repository.findByIdAndOwner(anyString(), eq(USER_ID))).thenAnswer(invocation ->
                    Optional.of(row(invocation.getArgument(0), "p-default", "image",
                            "assets/" + USER_ID + "/" + invocation.getArgument(0) + ".png")));
            AssetRow created = service.createImage(USER_ID, null, "工作图", List.of(),
                    null, sourceKind, null, null, null,
                    new byte[]{1}, "image/png", 100, 200, Instant.now(), "{\"description\":\"一只猫\"}");
            assertThat(created.storageKey()).startsWith("assets/" + USER_ID + "/");
        }
    }

    @Test
    void createImageRejectsUnknownSourceKind() {
        assertThatThrownBy(() -> service.createImage(USER_ID, null, null, null,
                null, "unknown-kind", null, null, null, new byte[]{1}, "image/png", null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("来源分类无效");
        verify(storage, never()).put(anyString(), any(), any());
    }

    @Test
    void listPassesExcludeWorkingAndIncludeDeleted() {
        when(repository.search(eq(USER_ID), isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(48),
                eq(true), eq(true))).thenReturn(new AssetRepository.AssetPage(List.of(), 0));
        AssetRepository.AssetPage page = service.list(USER_ID, null, null, null, null, null, 1, 48, true, true);
        assertThat(page.total()).isZero();
    }

    @Test
    void listDeletedReturnsRecycleBin() {
        when(repository.search(eq(USER_ID), isNull(), isNull(), isNull(), isNull(), eq("newest"), eq(1), eq(48),
                eq(false), eq(true))).thenReturn(new AssetRepository.AssetPage(List.of(), 0));
        AssetRepository.AssetPage page = service.listDeleted(USER_ID, null, 1, 48);
        assertThat(page.total()).isZero();
    }

    @Test
    void createImageIdempotentReturnsExistingByHash() {
        // S2 修复：同 hash 已存在（同用户任意项目）→ 直接返回已有素材，不重复创建/不 409
        AssetRow existing = new AssetRow("a1", USER_ID.toString(), "p1", "image", "已存在", "image/png", 3L, 1, 1,
                "[]", null, "canvas", "迁移导入", null, null, "assets/u1/a1.png", "abc",
                "{}", null, 0L,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"), null);
        when(repository.findByHash(eq(USER_ID), anyString())).thenReturn(Optional.of(existing));

        AssetRow result = service.createImageIdempotent(USER_ID, null, "重试图", List.of(),
                null, "canvas", null, null, null, new byte[]{1, 2, 3}, "image/png", null, null,
                Instant.now(), "{}");

        assertThat(result.id()).isEqualTo("a1");
        verify(repository, never()).insert(any(AssetEntity.class));
        verify(storage, never()).put(anyString(), any(), any());
    }

    @Test
    void createImageIdempotentCreatesWhenHashMissing() {
        when(repository.findByHash(eq(USER_ID), anyString())).thenReturn(Optional.empty());
        when(repository.findDuplicate(eq(USER_ID), eq("p-default"), anyString())).thenReturn(Optional.empty());
        when(repository.findByIdAndOwner(anyString(), eq(USER_ID))).thenAnswer(invocation ->
                Optional.of(row(invocation.getArgument(0), "p-default", "image",
                        "assets/" + USER_ID + "/" + invocation.getArgument(0) + ".png")));

        AssetRow created = service.createImageIdempotent(USER_ID, null, "新图", List.of(),
                null, "canvas", null, null, null, new byte[]{1}, "image/png", null, null,
                Instant.now(), "{}");

        assertThat(created.storageKey()).startsWith("assets/" + USER_ID + "/");
        verify(repository).insert(any(AssetEntity.class));
    }

    @Test
    void jsonIncludesExtraDeletedAtRefCount() {
        var json = AssetService.toJson(new AssetRow("a1", USER_ID.toString(), "p1", "image", "name", "image/png", 10L, 1, 1,
                "[]", null, "conversation", null, "conv-1", null, "assets/u1/a1.png", "abc",
                "{\"description\":\"猫\"}", Instant.parse("2026-07-01T00:00:00Z"), 2L,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"), null));
        assertThat(json).containsEntry("sourceKind", "conversation")
                .containsEntry("refCount", 2L)
                .containsKey("deletedAt")
                .containsEntry("extra", Map.of("description", "猫"));
    }

    @Test
    void batchMoveValidatesTargetProject() {
        when(projectService.owns(USER_ID, "p2")).thenReturn(false);
        assertThatThrownBy(() -> service.batchMove(USER_ID, List.of("a1"), "p2"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    void deleteByProjectCascadesObjects() {
        when(storage.delete(anyString())).thenReturn(true);
        when(repository.findByProject(USER_ID, "p1")).thenReturn(List.of(
                row("a1", "p1", "image", "assets/" + USER_ID + "/a1.png"),
                row("a2", "p1", "image", "assets/" + USER_ID + "/a2.png")));
        service.deleteByProject(USER_ID, "p1");
        verify(repository).deleteByIdsAndOwner(USER_ID, List.of("a1", "a2"));
        verify(storage).delete("assets/" + USER_ID + "/a1.png");
        verify(storage).delete("assets/" + USER_ID + "/a2.png");
    }

    @Test
    void jsonOmitsStorageKey() {
        var json = AssetService.toJson(row("a1", "p1", "image", "assets/u1/a1.png"));
        assertThat(json).doesNotContainKey("storageKey").containsEntry("id", "a1");
    }
}
