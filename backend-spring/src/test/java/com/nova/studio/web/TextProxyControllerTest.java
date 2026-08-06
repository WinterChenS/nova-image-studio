package com.nova.studio.web;

import com.nova.studio.accountpool.AccountHealthService;
import com.nova.studio.accountpool.AccountScheduler;
import com.nova.studio.accountpool.AccountService;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.accountpool.CatalogModelService;
import com.nova.studio.audit.UsageCollector;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.textproxy.TextProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T6 (WIN-28) — text proxy on the account pool: catalog UUID resolution,
 * scheduler selection at request time, and R3 retry boundary (non-streaming
 * full retry ≤2; SSE never switches account after the first byte — A21).
 */
class TextProxyControllerTest {

    private TextProxyService textProxyService;
    private CatalogModelService catalogModelService;
    private AccountScheduler scheduler;
    private AccountHealthService healthService;
    private TextProxyController controller;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID MODEL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACCOUNT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ACCOUNT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final AuthUser USER = new AuthUser(UUID.fromString("11111111-1111-1111-1111-111111111111"), "alice", "user");

    @BeforeEach
    void setUp() {
        textProxyService = mock(TextProxyService.class);
        catalogModelService = mock(CatalogModelService.class);
        scheduler = mock(AccountScheduler.class);
        healthService = mock(AccountHealthService.class);
        when(healthService.classify(any())).thenReturn(AccountHealthService.ErrorKind.SERVER_ERROR);
        when(catalogModelService.resolve(MODEL_ID)).thenReturn(Optional.of(textCatalogRow()));
        AccountService accountService = mock(AccountService.class);
        UsageCollector usageCollector = mock(UsageCollector.class);
        controller = new TextProxyController(textProxyService, catalogModelService, scheduler,
                healthService, accountService, usageCollector, 1_800_000, MAPPER);
    }

    private CatalogModelRepository.Row textCatalogRow() {
        return new CatalogModelRepository.Row(MODEL_ID, "text", "openai-chat-completions", "文本模型", "gpt-4o",
                "https://api.example.com/v1", "{}", null, true, null,
                Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"));
    }

    private AccountScheduler.SelectedAccount account(UUID id) {
        return new AccountScheduler.SelectedAccount(id, "账号", "openai-chat-completions", "http://upstream", "key-" + id);
    }

    private tools.jackson.databind.node.ObjectNode body(boolean stream) {
        var b = MAPPER.createObjectNode();
        b.put("modelId", MODEL_ID.toString());
        b.put("stream", stream);
        b.putArray("messages").addObject().put("role", "user").put("content", "hi");
        return b;
    }

    @Test
    void rejectsMissingModelId() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.proxy(MAPPER.createObjectNode(), USER, resp);
        assertThat(resp.getStatus()).isEqualTo(400);
        assertThat(resp.getContentAsString()).contains("modelId");
    }

    @Test
    void rejectsUnknownCatalogModel() throws Exception {
        when(catalogModelService.resolve(MODEL_ID)).thenReturn(Optional.empty());
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.proxy(body(false), USER, resp);
        assertThat(resp.getStatus()).isEqualTo(400);
    }

    @Test
    void rejectsImageCatalogModel() throws Exception {
        when(catalogModelService.resolve(MODEL_ID)).thenReturn(Optional.of(
                new CatalogModelRepository.Row(MODEL_ID, "image", "openai", "图片模型", "gpt-image-1",
                        "https://api.example.com/v1", "{}", null, true, null,
                        Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"))));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.proxy(body(false), USER, resp);
        assertThat(resp.getStatus()).isEqualTo(400);
    }

    @Test
    void returns503WhenNoAccountAvailable() throws Exception {
        when(scheduler.select(any(), any())).thenThrow(
                new HttpErrorException(503, "NO_AVAILABLE_ACCOUNT", "该模型暂无可用账号，请联系管理员"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.proxy(body(false), USER, resp);
        assertThat(resp.getStatus()).isEqualTo(503);
    }

    @Test
    void nonStreamHappyPathForwardsUpstreamBody() throws Exception {
        when(scheduler.select(any(), any())).thenReturn(account(ACCOUNT_A));
        when(textProxyService.buildTarget(anyString(), anyString(), anyString(), anyString(), any(Boolean.class)))
                .thenReturn(new TextProxyService.Target("http://upstream/v1/chat/completions", Map.of(), false));
        when(textProxyService.exchange(any(), any())).thenReturn(
                new TextProxyService.ProxyExchange(200, null, "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));

        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.proxy(body(false), USER, resp);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getContentAsString()).contains("\"content\":\"hi\"");
        verify(scheduler).release(ACCOUNT_A);
    }

    @Test
    void nonStreamRetriesWithAnotherAccountOn429() throws Exception {
        when(scheduler.select(any(), any())).thenReturn(account(ACCOUNT_A), account(ACCOUNT_B));
        when(textProxyService.buildTarget(anyString(), anyString(), anyString(), anyString(), any(Boolean.class)))
                .thenReturn(new TextProxyService.Target("http://upstream/v1/chat/completions", Map.of(), false));
        when(textProxyService.exchange(any(), any()))
                .thenReturn(new TextProxyService.ProxyExchange(429, null, "{\"error\":\"rate\"}"))
                .thenReturn(new TextProxyService.ProxyExchange(200, null, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));

        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.proxy(body(false), USER, resp);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getContentAsString()).contains("\"content\":\"ok\"");
        verify(textProxyService, times(2)).exchange(any(), any());
    }

    @Test
    void sseStreamPassthroughWithoutAccountSwitch() throws Exception {
        when(scheduler.select(any(), any())).thenReturn(account(ACCOUNT_A));
        when(textProxyService.buildTarget(anyString(), anyString(), anyString(), anyString(), any(Boolean.class)))
                .thenReturn(new TextProxyService.Target("http://upstream/v1/chat/completions", Map.of(), true));
        InputStream stream = new ByteArrayInputStream("data: {\"x\":1}\n\n".getBytes(StandardCharsets.UTF_8));
        when(textProxyService.exchange(any(), any())).thenReturn(new TextProxyService.ProxyExchange(200, stream, null));

        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.proxy(body(true), USER, resp);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getContentType()).contains("text/event-stream");
        assertThat(resp.getContentAsString()).contains("data: {\"x\":1}");
        verify(scheduler, times(1)).select(any(), any());   // 首字节后不换账号
        verify(scheduler).release(ACCOUNT_A);
    }

    @Test
    void sseConnectionFailureRetriesBeforeFirstByte() throws Exception {
        when(scheduler.select(any(), any())).thenReturn(account(ACCOUNT_A), account(ACCOUNT_B));
        when(textProxyService.buildTarget(anyString(), anyString(), anyString(), anyString(), any(Boolean.class)))
                .thenReturn(new TextProxyService.Target("http://upstream/v1/chat/completions", Map.of(), true));
        when(textProxyService.exchange(any(), any()))
                .thenThrow(new IllegalStateException("connect timed out"))
                .thenReturn(new TextProxyService.ProxyExchange(200,
                        new ByteArrayInputStream("data: {\"x\":1}\n\n".getBytes(StandardCharsets.UTF_8)), null));

        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.proxy(body(true), USER, resp);

        assertThat(resp.getStatus()).isEqualTo(200);
        verify(textProxyService, times(2)).exchange(any(), any());
    }
}
