package com.nova.studio.migration;

import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.canvas.CanvasProjectRepository;
import com.nova.studio.conversation.ConversationRepository;
import com.nova.studio.conversation.ConversationMessageRepository;
import com.nova.studio.history.HistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WIN-40 T7 — 迁移框架：Agent/画布/历史批量导入幂等（唯一键去重，FR-7.2/7.3）、
 * 图片上传委托 assets（hash 去重链路）。
 */
class MigrationServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ConversationRepository conversationRepository;
    private ConversationMessageRepository messageRepository;
    private CanvasProjectRepository canvasRepository;
    private HistoryRepository historyRepository;
    private AssetService assetService;
    private MigrationService service;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(ConversationMessageRepository.class);
        canvasRepository = mock(CanvasProjectRepository.class);
        historyRepository = mock(HistoryRepository.class);
        assetService = mock(AssetService.class);
        service = new MigrationService(conversationRepository, messageRepository,
                canvasRepository, historyRepository, assetService);
    }

    @Test
    void agentImportCreatesAndSkipsDuplicates() {
        when(conversationRepository.findByIdAndOwner("conv-1", USER_ID)).thenReturn(Optional.empty());
        when(conversationRepository.findByIdAndOwner("conv-2", USER_ID)).thenReturn(
                Optional.of(new ConversationRepository.ConversationRow("conv-2", USER_ID.toString(),
                        "旧会话", "active", null, false, null, null, null,
                        java.time.Instant.parse("2026-01-01T00:00:00Z"),
                        java.time.Instant.parse("2026-01-01T00:00:00Z"), null)));

        ObjectMapper mapper = new ObjectMapper();
        var body = mapper.createObjectNode();
        var conversations = body.putArray("conversations");
        conversations.addObject()
                .put("id", "conv-1")
                .put("title", "导入会话")
                .putArray("messages")
                .addObject().put("id", "msg-1").put("role", "user").put("text", "你好");
        conversations.addObject().put("id", "conv-2").put("title", "已存在");

        MigrationService.MigrationSummary summary = service.importConversations(USER_ID, body);
        assertThat(summary.created()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        verify(conversationRepository).insert(any());
        verify(messageRepository).insert(any());
    }

    @Test
    void agentImportRejectsMissingConversationsArray() {
        ObjectMapper mapper = new ObjectMapper();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.importConversations(USER_ID, mapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canvasImportCreatesProjectWithDefaults() {
        when(canvasRepository.findByIdAndOwner("proj-1", USER_ID)).thenReturn(Optional.empty());
        ObjectMapper mapper = new ObjectMapper();
        var body = mapper.createObjectNode();
        body.putArray("projects").addObject()
                .put("id", "proj-1")
                .put("title", "导入画布")
                .putArray("nodes").addObject().put("id", "n1").putObject("image").put("assetId", "a1");

        MigrationService.MigrationSummary summary = service.importCanvasProjects(USER_ID, body);
        assertThat(summary.created()).isEqualTo(1);
        verify(canvasRepository).insert(any());
    }

    @Test
    void canvasImportSkipsExisting() {
        when(canvasRepository.findByIdAndOwner("proj-1", USER_ID)).thenReturn(
                Optional.of(new CanvasProjectRepository.CanvasRow("proj-1", USER_ID.toString(), "画布",
                        "[]", "[]", "lines", false, "{\"x\":0,\"y\":0,\"k\":1}", 1L, null,
                        java.time.Instant.parse("2026-01-01T00:00:00Z"),
                        java.time.Instant.parse("2026-01-01T00:00:00Z"))));
        ObjectMapper mapper = new ObjectMapper();
        var body = mapper.createObjectNode();
        body.putArray("projects").addObject().put("id", "proj-1").put("title", "已存在");
        MigrationService.MigrationSummary summary = service.importCanvasProjects(USER_ID, body);
        assertThat(summary.created()).isZero();
        assertThat(summary.skipped()).isEqualTo(1);
        verify(canvasRepository, never()).insert(any());
    }

    @Test
    void historyImportWritesToHistoriesWithType() {
        when(historyRepository.existsByIdAndOwner("rev-1", USER_ID)).thenReturn(false);
        ObjectMapper mapper = new ObjectMapper();
        var body = mapper.createObjectNode();
        body.putArray("items").addObject()
                .put("id", "rev-1")
                .put("type", "reverse")
                .putObject("payload").put("text", "猫猫");
        MigrationService.MigrationSummary summary = service.importHistories(USER_ID, "reverse", body);
        assertThat(summary.created()).isEqualTo(1);
        verify(historyRepository).insert(any());
    }

    @Test
    void uploadImageValidatesSourceKind() {
        ObjectMapper mapper = new ObjectMapper();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.uploadImage(USER_ID, new byte[]{1}, "image/png", "bogus-kind", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(assetService, never()).createImage(eq(USER_ID), any(), any(), any(), any(),
                anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void uploadImageUsesIdempotentCreate() {
        // S2 修复：迁移上传走 createImageIdempotent（同 hash 返回已有 assetId，重试引用不悬挂）
        when(assetService.createImageIdempotent(eq(USER_ID), any(), any(), any(), any(), eq("canvas"),
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AssetRepository.AssetRow("a1", USER_ID.toString(), "p1", "image", "迁移图",
                        "image/png", 3L, null, null, "[]", null, "canvas", "迁移导入", null,
                        null, "assets/u1/a1.png", "abc", "{}", null, 0L,
                        java.time.Instant.parse("2026-08-01T00:00:00Z"),
                        java.time.Instant.parse("2026-08-01T00:00:00Z"), null));
        AssetRepository.AssetRow row = service.uploadImage(USER_ID, new byte[]{1}, "image/png",
                "canvas", null, null);
        assertThat(row.id()).isEqualTo("a1");
        verify(assetService).createImageIdempotent(eq(USER_ID), any(), any(), any(), any(), eq("canvas"),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
