package com.nova.studio.history;

import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WIN-41 (T12/T13, ADR-39/42) — 统一历史：反推双槽/草稿（每用户至多一条）、GIF 状态机
 * 迁移校验、成品上传、历史删除（联动素材）、配额滚动清理。
 */
class HistoryServiceTest {

    private HistoryRepository repository;
    private AssetService assetService;
    private SettingsService settingsService;
    private HistoryService service;
    private ObjectMapper objectMapper;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(HistoryRepository.class);
        assetService = mock(AssetService.class);
        settingsService = mock(SettingsService.class);
        objectMapper = new ObjectMapper();
        service = new HistoryService(repository, assetService, settingsService, objectMapper);
        when(settingsService.getInt(eq(userId), anyString(), anyInt())).thenReturn(500);
    }

    private ObjectNode reverseBody(String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", text);
        body.put("model", "gemini-2.5-flash");
        body.put("mode", "simple");
        return body;
    }

    @Test
    void saveReverseRecordPersistsPayloadAndRollsBackCap() {
        when(repository.insert(any())).thenAnswer(inv -> {
            HistoryEntity entity = inv.getArgument(0);
            return entity.getId();
        });
        when(repository.findByIdAndOwner(anyString(), eq(userId)))
                .thenAnswer(inv -> Optional.of(entity(inv.getArgument(0).toString(), "completed")));
        ArgumentCaptor<HistoryEntity> captor = ArgumentCaptor.forClass(HistoryEntity.class);

        HistoryRepository.HistoryRow row = service.saveReverseRecord(userId, reverseBody("反推结果"));
        assertThat(row.status()).isEqualTo("completed");
        verify(repository).insert(captor.capture());
        HistoryEntity inserted = captor.getValue();
        assertThat(inserted.getType()).isEqualTo("reverse");
        assertThat(inserted.getPayload()).contains("\"text\":\"反推结果\"");
        assertThat(inserted.getTitle()).isEqualTo("反推结果");
        verify(repository).deleteOldestBeyondCap(eq(userId), eq("reverse"), anyInt());
    }

    @Test
    void reverseDraftUpsertKeepsSingleRowPerUser() {
        HistoryEntity existing = new HistoryEntity();
        existing.setId("draft-1");
        existing.setUserId(userId.toString());
        existing.setType("reverse");
        existing.setStatus("draft");
        when(repository.findReverseDraft(userId)).thenReturn(Optional.of(existing));
        when(repository.findByIdAndOwner("draft-1", userId)).thenReturn(Optional.of(existing));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", "草稿文字");
        body.putArray("imageIds").add("asset-9");
        service.saveReverseDraft(userId, body);

        ArgumentCaptor<HistoryEntity> updateCaptor = ArgumentCaptor.forClass(HistoryEntity.class);
        verify(repository).update(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getImageIds()).contains("asset-9");
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void reverseDraftEmptyClearsExisting() {
        HistoryEntity existing = new HistoryEntity();
        existing.setId("draft-1");
        when(repository.findReverseDraft(userId)).thenReturn(Optional.of(existing));
        HistoryRepository.HistoryRow result = service.saveReverseDraft(userId,
                objectMapper.createObjectNode());
        assertThat(result).isNull();
        verify(repository).deleteByIdAndOwner("draft-1", userId);
    }

    @Test
    void gifJobCreationPersistsPayloadWithEncodeMode() {
        when(repository.insert(any())).thenAnswer(inv -> ((HistoryEntity) inv.getArgument(0)).getId());
        when(repository.findByIdAndOwner(anyString(), eq(userId)))
                .thenAnswer(inv -> Optional.of(entity(inv.getArgument(0).toString(), "completed")));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("prompt", "做一张眨眼 GIF");
        body.put("model", "gpt-image-2");
        body.put("loop", true);
        body.put("closedLoop", false);
        body.putArray("refImageAssetIds").add("a1").add("a2");

        HistoryRepository.HistoryRow row = service.createGifJob(userId, body);
        ArgumentCaptor<HistoryEntity> captor = ArgumentCaptor.forClass(HistoryEntity.class);
        verify(repository).insert(captor.capture());
        HistoryEntity inserted = captor.getValue();
        assertThat(inserted.getStatus()).isEqualTo("idle");
        assertThat(inserted.getPayload()).contains("\"encodeMode\":\"client\"")
                .contains("\"prompt\":\"做一张眨眼 GIF\"")
                .contains("a1");
    }

    @Test
    void gifStateMachineValidatesTransitions() {
        // 首次返回 idle，之后返回 generating_grid（顺序桩）
        when(repository.findByIdAndOwner("g1", userId))
                .thenReturn(Optional.of(entity("g1", "idle")), Optional.of(entity("g1", "generating_grid")));

        // idle → generating_grid 合法
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("status", "generating_grid");
        patch.put("taskId", "task-1");
        service.patchGifJob(userId, "g1", patch);

        // generating_grid → done 非法（必须经 review_grid）
        when(repository.findByIdAndOwner("g1", userId))
                .thenReturn(Optional.of(entity("g1", "generating_grid")));
        ObjectNode bad = objectMapper.createObjectNode();
        bad.put("status", "done");
        assertThatThrownBy(() -> service.patchGifJob(userId, "g1", bad))
                .isInstanceOf(HttpErrorException.class)
                .satisfies(e -> assertThat(((HttpErrorException) e).getStatusCode()).isEqualTo(409));
    }

    @Test
    void uploadGifResultCreatesAssetAndMarksDone() {
        when(repository.findByIdAndOwner("g1", userId))
                .thenReturn(Optional.of(entity("g1", "review_grid")), Optional.of(entity("g1", "done")));
        when(assetService.createImageIdempotent(eq(userId), any(), any(), any(), any(),
                eq("gif"), any(), eq("g1"), any(), any(byte[].class), any(), any(), any(),
                any(), any())).thenReturn(assetRow("asset-gif-1"));

        HistoryRepository.HistoryRow done = service.uploadGifResult(userId, "g1",
                new byte[]{1, 2}, "image/gif");
        assertThat(done.status()).isEqualTo("done");
        ArgumentCaptor<HistoryEntity> captor = ArgumentCaptor.forClass(HistoryEntity.class);
        verify(repository).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("done");
        assertThat(captor.getValue().getImageIds()).contains("asset-gif-1");
    }

    @Test
    void uploadGifResultRejectsWhenNotInEncodableState() {
        when(repository.findByIdAndOwner("g1", userId)).thenReturn(Optional.of(entity("g1", "idle")));
        assertThatThrownBy(() -> service.uploadGifResult(userId, "g1", new byte[]{1}, "image/gif"))
                .isInstanceOf(HttpErrorException.class)
                .satisfies(e -> assertThat(((HttpErrorException) e).getStatusCode()).isEqualTo(409));
    }

    @Test
    void deleteHistoryCascadesAssets() {
        when(repository.findByIdAndOwner("g1", userId)).thenReturn(Optional.of(entity("g1", "done")));
        when(repository.deleteByIdAndOwner("g1", userId)).thenReturn(1);
        service.deleteHistory(userId, "g1");
        verify(repository).deleteByIdAndOwner("g1", userId);
    }

    @Test
    void crossUserHistoryIs404() {
        when(repository.findByIdAndOwner("g1", userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getHistory(userId, "g1"))
                .isInstanceOf(HttpErrorException.class)
                .satisfies(e -> assertThat(((HttpErrorException) e).getStatusCode()).isEqualTo(404));
    }

    // ===== helpers =====

    private HistoryEntity entity(String id, String status) {
        HistoryEntity entity = new HistoryEntity();
        entity.setId(id);
        entity.setUserId(userId.toString());
        entity.setType("gif");
        entity.setStatus(status);
        entity.setTitle("GIF 标题");
        entity.setPayload("{}");
        entity.setImageIds("[]");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }


    private AssetRepository.AssetRow assetRow(String id) {
        return new AssetRepository.AssetRow(id, userId.toString(), null, "image", "GIF 成品",
                "image/gif", 10L, null, null, "[]", "GIF 成品", "gif", "GIF 成品", "g1",
                null, "key", "hash", "{}", null, 0L, Instant.now(), Instant.now(), Instant.now());
    }
}
