package com.nova.studio.agent;

import com.nova.studio.accountpool.AccountHealthService;
import com.nova.studio.accountpool.AccountScheduler;
import com.nova.studio.accountpool.AccountService;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.accountpool.CatalogModelService;
import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.audit.UsageCollector;
import com.nova.studio.conversation.ConversationMessageRepository;
import com.nova.studio.conversation.ConversationRepository;
import com.nova.studio.conversation.ConversationService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.SettingsService;
import com.nova.studio.textproxy.TextProxyService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WIN-41 (T9/T10) — Agent 会话托管：SSE 统一事件输出（MockWebServer 上游）、
 * 消息落库（clientMessageId 一致）、usage ref_type='agent'、会话单飞锁 409、
 * 重试语义、describe 端点。
 */
class AgentChatServiceTest {

    private MockWebServer server;
    private AgentChatService service;

    private ConversationService conversationService;
    private ConversationMessageRepository messageRepository;
    private AssetService assetService;
    private SettingsService settingsService;
    private CatalogModelService catalogModelService;
    private AccountScheduler accountScheduler;
    private AccountService accountService;
    private UsageCollector usageCollector;
    private ObjectMapper objectMapper;

    private final UUID userId = UUID.randomUUID();
    private final String conversationId = "conv-1";
    private final UUID modelId = UUID.randomUUID();

    /** 事件记录 sink。 */
    private static final class Recorder implements AgentChatService.SseWriter {
        final List<String> types = new ArrayList<>();
        final List<Map<String, Object>> datas = new ArrayList<>();

        @Override
        public void event(String type, Map<String, Object> data) {
            types.add(type);
            datas.add(data);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        objectMapper = new ObjectMapper();

        conversationService = mock(ConversationService.class);
        messageRepository = mock(ConversationMessageRepository.class);
        assetService = mock(AssetService.class);
        settingsService = mock(SettingsService.class);
        catalogModelService = mock(CatalogModelService.class);
        accountScheduler = mock(AccountScheduler.class);
        accountService = mock(AccountService.class);
        usageCollector = mock(UsageCollector.class);
        AccountHealthService accountHealthService = mock(AccountHealthService.class);

        service = new AgentChatService(conversationService, messageRepository, assetService,
                settingsService, new AgentRequestBodyBuilder(objectMapper),
                new AgentStreamTranslator(objectMapper), new ContextCompressor(),
                new TextProxyService(objectMapper, 30_000), catalogModelService,
                accountScheduler, accountHealthService, accountService, usageCollector,
                objectMapper, 45_000);

        // 默认桩
        ConversationRepository.ConversationRow conversation = new ConversationRepository.ConversationRow(
                conversationId, userId.toString(), "测试会话", "active", null, false,
                null, null, null, Instant.now(), Instant.now(), Instant.now());
        when(conversationService.getOwned(userId, conversationId)).thenReturn(conversation);
        when(conversationService.appendMessage(eq(userId), eq(conversationId), any(JsonNode.class), any()))
                .thenAnswer(inv -> {
                    JsonNode body = inv.getArgument(2);
                    String id = inv.getArgument(3);
                    return new ConversationMessageRepository.MessageRow(
                            id == null ? UUID.randomUUID().toString() : id, conversationId,
                            userId.toString(), body.path("role").asText("user"),
                            body.path("text").asText(""), null, "[]", null, null, false, false, Instant.now());
                });
        when(messageRepository.listByConversation(eq(conversationId), eq(userId.toString()), any(), anyInt()))
                .thenReturn(new ConversationMessageRepository.MessagePage(List.of(), null));
        when(assetService.listConversationImages(userId, conversationId)).thenReturn(List.of());
        when(catalogModelService.listPublicCatalog()).thenReturn(List.of());
        when(settingsService.getInt(eq(userId), anyString(), anyInt())).thenReturn(60);
        when(accountService.findById(any(UUID.class))).thenReturn(Optional.empty());

        CatalogModelRepository.Row modelRow = new CatalogModelRepository.Row(
                modelId, "text", "openai-responses", "GPT-5.4 Mini", "gpt-5.4-mini",
                server.url("/").toString(), "{}", null, true, null, Instant.now(), Instant.now());
        when(catalogModelService.resolve(modelId)).thenReturn(Optional.of(modelRow));
        when(accountScheduler.select(eq(modelRow), any())).thenReturn(
                new AccountScheduler.SelectedAccount(UUID.randomUUID(), "acc-1", "openai-responses",
                        server.url("/").toString(), "sk-test"));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private ObjectNode chatBody(String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelId.toString());
        body.put("text", text);
        body.put("clientMessageId", "client-msg-1");
        return body;
    }

    private MockResponse sse(String... chunks) {
        StringBuilder sb = new StringBuilder();
        for (String chunk : chunks) {
            sb.append(chunk);
        }
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sb.toString());
    }

    @Test
    void streamChatEmitsUnifiedEventsAndPersistsMessages() throws Exception {
        server.enqueue(sse(
                "event: response.reasoning_summary_text.delta\ndata: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"分析中\"}\n\n",
                "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}\n\n",
                "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"output_text\":\"你好\"}}\n\n"));

        Recorder recorder = new Recorder();
        AgentChatService.ChatResult result = service.streamChat(userId, conversationId, chatBody("hi"), recorder);

        assertThat(recorder.types).containsExactly("reasoning", "delta", "done");
        assertThat(recorder.datas.get(0)).containsEntry("text", "分析中");
        assertThat(recorder.datas.get(1)).containsEntry("text", "你好");
        // done 携带 assistant 消息 id
        String assistantId = (String) recorder.datas.get(2).get("messageId");
        assertThat(assistantId).isNotBlank();
        assertThat(result.assistantMessageId()).isEqualTo(assistantId);

        // 用户消息以 clientMessageId 落库
        verify(conversationService).appendMessage(eq(userId), eq(conversationId), any(JsonNode.class), eq("client-msg-1"));
        // assistant 消息落库
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(conversationService, org.mockito.Mockito.times(2))
                .appendMessage(eq(userId), eq(conversationId), captor.capture(), any());
        JsonNode assistant = captor.getAllValues().get(1);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(assistant.path("text").asText()).isEqualTo("你好");
        assertThat(assistant.path("reasoning").asText()).isEqualTo("分析中");

        // usage ref_type=agent
        ArgumentCaptor<UsageCollector.AgentUsage> usageCaptor =
                ArgumentCaptor.forClass(UsageCollector.AgentUsage.class);
        verify(usageCollector).recordAgentUsage(usageCaptor.capture());
        assertThat(usageCaptor.getValue().refId()).isEqualTo("client-msg-1");
        assertThat(usageCaptor.getValue().failed()).isFalse();
    }

    @Test
    void streamChatWithToolCallEmitsProposalNotAssistantMessage() throws Exception {
        server.enqueue(sse(
                "event: response.function_call_arguments.delta\ndata: {\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{\\\"action\\\":\\\"generate\\\",\\\"prompt\\\":\\\"一只橘猫\\\",\\\"reason\\\":\\\"生成新图\\\",\\\"referenced_image_ids\\\":[],\\\"requested_aspect_ratio\\\":null,\\\"suggested_aspect_ratio\\\":\\\"1:1\\\"}\"}\n\n",
                "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"output_text\":\"\",\"output\":[{\"type\":\"function_call\",\"name\":\"propose_image_action\",\"arguments\":\"{\\\"action\\\":\\\"generate\\\",\\\"prompt\\\":\\\"一只橘猫\\\",\\\"reason\\\":\\\"生成新图\\\",\\\"referenced_image_ids\\\":[],\\\"requested_aspect_ratio\\\":null,\\\"suggested_aspect_ratio\\\":\\\"1:1\\\"}\"}]}}\n\n"));

        Recorder recorder = new Recorder();
        service.streamChat(userId, conversationId, chatBody("画一只猫"), recorder);

        assertThat(recorder.types).containsExactly("proposal", "done");
        Map<String, Object> proposal = recorder.datas.get(0);
        assertThat(proposal.get("action")).isEqualTo("generate");
        assertThat(proposal.get("prompt")).isEqualTo("一只橘猫");
        // S-2（质量评审）：proposal 路径 done.messageId 回填用户消息 id（契约补全）
        Map<String, Object> done = recorder.datas.get(1);
        assertThat(done.get("messageId")).isEqualTo("client-msg-1");
        // 提案路径不落 assistant 消息（仅用户消息）
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(conversationService, org.mockito.Mockito.times(1))
                .appendMessage(eq(userId), eq(conversationId), captor.capture(), any());
        assertThat(captor.getValue().path("role").asText()).isEqualTo("user");
    }

    @Test
    void concurrentStreamReturns409() throws Exception {
        assertThat(service.tryAcquireStream(conversationId)).isTrue();
        try {
            assertThatThrownBy(() -> service.streamChat(userId, conversationId, chatBody("hi"), new Recorder()))
                    .isInstanceOf(HttpErrorException.class)
                    .satisfies(e -> {
                        HttpErrorException ex = (HttpErrorException) e;
                        assertThat(ex.getStatusCode()).isEqualTo(409);
                        assertThat(ex.getCode()).isEqualTo("CONCURRENT_STREAM");
                    });
        } finally {
            service.releaseStream(conversationId);
        }
        // 释放后可再次发起
        server.enqueue(sse("event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"output_text\":\"ok\"}}\n\n"));
        Recorder recorder = new Recorder();
        service.streamChat(userId, conversationId, chatBody("hi"), recorder);
        assertThat(recorder.types).contains("done");
    }

    @Test
    void retryOnUpstream5xxThenSuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));
        server.enqueue(sse(
                "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"重试成功\"}\n\n",
                "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"output_text\":\"重试成功\"}}\n\n"));
        when(accountScheduler.select(any(), any())).thenAnswer(inv -> {
            Set<?> tried = (Set<?>) inv.getArgument(1);
            return new AccountScheduler.SelectedAccount(UUID.randomUUID(), "acc-" + tried.size(),
                    "openai-responses", server.url("/").toString(), "sk-test");
        });

        Recorder recorder = new Recorder();
        service.streamChat(userId, conversationId, chatBody("hi"), recorder);

        assertThat(recorder.types).contains("retry");
        assertThat(recorder.datas.stream().filter(d -> d.containsKey("attempt")).findFirst())
                .get()
                .satisfies(d -> assertThat(d.get("attempt")).isEqualTo(2));
        assertThat(recorder.types).endsWith("done");
    }

    @Test
    void describeImageReturnsExtractedText() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"output_text\":\"一只橘猫在沙发上\"}"));
        when(assetService.getFile(userId, "asset-1")).thenReturn(Optional.of(
                new AssetService.StoredAssetFile(new byte[]{1, 2, 3}, "image/png", "a.png")));

        String description = service.describeImage(userId, "asset-1", modelId.toString());
        assertThat(description).isEqualTo("一只橘猫在沙发上");
        ArgumentCaptor<UsageCollector.AgentUsage> captor =
                ArgumentCaptor.forClass(UsageCollector.AgentUsage.class);
        verify(usageCollector).recordAgentUsage(captor.capture());
        assertThat(captor.getValue().refId()).isEqualTo("describe-asset-1");
    }

    @Test
    void streamChatMissingModelReturns400() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", "hi");
        assertThatThrownBy(() -> service.streamChat(userId, conversationId, body, new Recorder()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void accountPoolExhaustedEmitsErrorEvent() throws Exception {
        when(accountScheduler.select(any(), any()))
                .thenThrow(new HttpErrorException(503, "NO_ACCOUNT", "无可用账号"));
        Recorder recorder = new Recorder();
        AgentChatService.ChatResult result = service.streamChat(userId, conversationId, chatBody("hi"), recorder);
        assertThat(recorder.types).contains("error");
        Map<String, Object> error = recorder.datas.get(recorder.types.indexOf("error"));
        assertThat(error.get("code")).isEqualTo("NO_ACCOUNT");
        assertThat(result.assistantMessageId()).isNull();
        verify(conversationService, org.mockito.Mockito.times(1))
                .appendMessage(eq(userId), eq(conversationId), any(JsonNode.class), any());
    }

    @Test
    void emptyResponseIsTreatedAsError() throws Exception {
        server.enqueue(sse("event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"output_text\":\"\"}}\n\n"));
        Recorder recorder = new Recorder();
        service.streamChat(userId, conversationId, chatBody("hi"), recorder);
        assertThat(recorder.types).contains("error");
        assertThat(recorder.datas.stream().filter(d -> "EMPTY_RESPONSE".equals(d.get("code"))).findFirst()).isPresent();
    }
}
