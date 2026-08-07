package com.nova.studio.conversation;

import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
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
 * WIN-40 T3 — Agent 会话持久化服务：CRUD、配额校验（AC-11）、title 自动摘要（C1）、
 * pending 读写（FR-1.2）、context_summary 读写、图片目录（ADR-36）、属主隔离 404（AC-10）。
 */
class ConversationServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ConversationRepository repository;
    private ConversationMessageRepository messageRepository;
    private AssetService assetService;
    private SettingsService settingsService;
    private ConversationService service;

    private ConversationRepository.ConversationRow row(String id, String status, String title) {
        return new ConversationRepository.ConversationRow(id, USER_ID.toString(), title, status,
                null, false, null, null, null,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"), null);
    }

    @BeforeEach
    void setUp() {
        repository = mock(ConversationRepository.class);
        messageRepository = mock(ConversationMessageRepository.class);
        assetService = mock(AssetService.class);
        settingsService = mock(SettingsService.class);
        service = new ConversationService(repository, messageRepository, assetService,
                settingsService, new ObjectMapper());
        when(settingsService.getInt(eq(USER_ID), eq("limit.agentConversationCap"), eq(100))).thenReturn(100);
        when(settingsService.getInt(eq(USER_ID), eq("limit.agentMessageCapPerConversation"), eq(500))).thenReturn(500);
    }

    @Test
    void createStoresConversationWithDefaultTitle() {
        when(repository.countActive(USER_ID)).thenReturn(0L);
        when(repository.findByIdAndOwner(anyString(), eq(USER_ID))).thenAnswer(invocation ->
                Optional.of(row(invocation.getArgument(0), "active", "未命名会话")));
        ConversationRepository.ConversationRow created = service.create(USER_ID, null);
        assertThat(created.status()).isEqualTo("active");
        verify(repository).insert(any(ConversationEntity.class));
    }

    @Test
    void createRejectsWhenQuotaExceeded() {
        when(repository.countActive(USER_ID)).thenReturn(100L);
        assertThatThrownBy(() -> service.create(USER_ID, null))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("QUOTA_EXCEEDED");
                });
        verify(repository, never()).insert(any(ConversationEntity.class));
    }

    @Test
    void getReturns404ForCrossUser() {
        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOwned(USER_ID, "c1"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    void appendMessageSetsTitleFromFirstUserMessage() {
        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.of(row("c1", "active", "未命名会话")));
        when(messageRepository.countByConversation("c1", USER_ID.toString())).thenReturn(0L);
        when(messageRepository.findByIdAndOwner(anyString(), eq("c1"), eq(USER_ID))).thenAnswer(invocation ->
                Optional.of(new ConversationMessageRepository.MessageRow(invocation.getArgument(0), "c1",
                        USER_ID.toString(), "user", "请帮我生成一张赛博朋克风格的猫咪图片", null, null, null, null, null, false,
                        Instant.parse("2026-08-01T00:00:00Z"))));

        ObjectMapper mapper = new ObjectMapper();
        service.appendMessage(USER_ID, "c1", mapper.createObjectNode()
                .put("role", "user").put("text", "请帮我生成一张赛博朋克风格的猫咪图片"));

        // title 自动摘要截断 24 字（C1/ADR-38）
        org.mockito.ArgumentCaptor<ConversationEntity> captor =
                org.mockito.ArgumentCaptor.forClass(ConversationEntity.class);
        verify(repository).update(captor.capture());
        assertThat(captor.getValue().getTitle()).isNotEqualTo("未命名会话");
        assertThat(captor.getValue().getTitle().length()).isLessThanOrEqualTo(25);
        assertThat(captor.getValue().getLastMessageAt()).isNotNull();
    }

    @Test
    void appendMessageRejectsWhenMessageCapExceeded() {
        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.of(row("c1", "active", "标题")));
        when(messageRepository.countByConversation("c1", USER_ID.toString())).thenReturn(500L);
        ObjectMapper mapper = new ObjectMapper();
        assertThatThrownBy(() -> service.appendMessage(USER_ID, "c1",
                mapper.createObjectNode().put("role", "user").put("text", "你好")))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("QUOTA_EXCEEDED");
                });
    }

    @Test
    void appendMessageRejectsOnDeletedConversation() {
        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.of(row("c1", "deleted", "标题")));
        ObjectMapper mapper = new ObjectMapper();
        assertThatThrownBy(() -> service.appendMessage(USER_ID, "c1",
                mapper.createObjectNode().put("role", "user").put("text", "你好")))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("CONVERSATION_DELETED");
                });
    }

    @Test
    void patchValidatesPendingKind() {
        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.of(row("c1", "active", "标题")));
        ObjectMapper mapper = new ObjectMapper();
        assertThatThrownBy(() -> service.patch(USER_ID, "c1",
                mapper.createObjectNode().set("pending",
                        mapper.createObjectNode().put("kind", "bogus"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pending.kind");
    }

    @Test
    void patchWritesPendingAndContextSummary() {
        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.of(row("c1", "active", "标题")));
        ObjectMapper mapper = new ObjectMapper();
        service.patch(USER_ID, "c1", mapper.createObjectNode()
                .set("pending", mapper.createObjectNode().put("kind", "proposal").put("taskId", "t1"))
                .set("contextSummary", mapper.createObjectNode().put("text", "摘要").put("foldedCount", 3)));

        org.mockito.ArgumentCaptor<ConversationEntity> captor =
                org.mockito.ArgumentCaptor.forClass(ConversationEntity.class);
        verify(repository).update(captor.capture());
        assertThat(captor.getValue().getPending()).contains("\"kind\":\"proposal\"");
        assertThat(captor.getValue().getContextSummary()).contains("\"foldedCount\":3");
    }

    @Test
    void softDeleteThenRestore() {
        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.of(row("c1", "active", "标题")));
        service.softDelete(USER_ID, "c1");
        verify(repository).update(org.mockito.ArgumentMatchers.argThat(p ->
                "deleted".equals(p.getStatus()) && p.getDeletedAt() != null));

        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.of(row("c1", "deleted", "标题")));
        service.restore(USER_ID, "c1");
        verify(repository).update(org.mockito.ArgumentMatchers.argThat(p ->
                "active".equals(p.getStatus()) && p.getDeletedAt() == null));
    }

    @Test
    void withdrawRejectsNonWithdrawableMessage() {
        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.of(row("c1", "active", "标题")));
        when(messageRepository.findByIdAndOwner("m1", "c1", USER_ID)).thenReturn(Optional.of(
                new ConversationMessageRepository.MessageRow("m1", "c1", USER_ID.toString(), "assistant",
                        "text", null, null, null, null, null, false,
                        Instant.parse("2026-08-01T00:00:00Z"))));
        assertThatThrownBy(() -> service.withdraw(USER_ID, "c1", "m1"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("NOT_WITHDRAWABLE");
                });
    }

    @Test
    void uploadImageDelegatesToAssetsWithConversationSource() {
        when(repository.findByIdAndOwner("c1", USER_ID)).thenReturn(Optional.of(row("c1", "active", "标题")));
        when(assetService.createImage(eq(USER_ID), any(), any(), any(), any(), eq("conversation"),
                any(), eq("c1"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AssetRepository.AssetRow("a1", USER_ID.toString(), "p1", "image", "会话图",
                        "image/png", 10L, 100, 200, "[]", null, "conversation", "会话图片", "c1",
                        null, "assets/u1/a1.png", "abc", "{}", null, 0L,
                        Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"), null));

        AssetRepository.AssetRow created = service.uploadImage(USER_ID, "c1",
                new byte[]{1, 2, 3}, "image/png", "uploaded", "一只猫", 100, 200);
        assertThat(created.sourceKind()).isEqualTo("conversation");
        assertThat(created.sourceRef()).isEqualTo("c1");
    }

    @Test
    void listImagesRequiresOwnership() {
        when(repository.findByIdAndOwner("c1", OTHER_USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.listImages(OTHER_USER, "c1"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(404));
    }
}
