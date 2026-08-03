package com.nova.studio.textproxy;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1.5 — real upstream exchange against a mocked HTTP server: JSON passthrough
 * (status + body), non-2xx forwarding, and SSE streaming chunk forwarding with
 * exact status.
 */
class TextProxyServiceExchangeTest {

    private MockWebServer server;
    private TextProxyService service;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        service = new TextProxyService(new ObjectMapper(), 30_000);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void jsonPassthroughForwardsStatusAndBody() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));

        TextProxyService.Target target = service.buildTarget(
                "openai-chat-completions", server.url("/").toString(), "sk-x", "gpt-4o", false);
        var exchange = service.exchange(target, new ObjectMapper().createObjectNode());
        assertThat(exchange.status()).isEqualTo(200);
        assertThat(exchange.streamed()).isFalse();
        assertThat(exchange.jsonBody()).contains("\"content\":\"hi\"");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer sk-x");
    }

    @Test
    void non2xxStatusForwardedAsJson() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"bad request\"}}"));

        TextProxyService.Target target = service.buildTarget(
                "openai-chat-completions", server.url("/").toString(), "sk-x", "gpt-4o", false);
        var exchange = service.exchange(target, new ObjectMapper().createObjectNode());
        assertThat(exchange.status()).isEqualTo(400);
        assertThat(exchange.streamed()).isFalse();
        assertThat(exchange.jsonBody()).contains("bad request");
    }

    @Test
    void sseStreamingForwardsChunks() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"x\":1}\n\ndata: {\"x\":2}\n\n"));

        TextProxyService.Target target = service.buildTarget(
                "openai-chat-completions", server.url("/").toString(), "sk-x", "gpt-4o", true);
        var exchange = service.exchange(target, new ObjectMapper().createObjectNode());
        assertThat(exchange.status()).isEqualTo(200);
        assertThat(exchange.streamed()).isTrue();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exchange.transferTo(out);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("data: {\"x\":1}").contains("data: {\"x\":2}");
    }

    @Test
    void streamRequestedButUpstreamErrorFallsBackToJson() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"boom\"}"));

        TextProxyService.Target target = service.buildTarget(
                "openai-chat-completions", server.url("/").toString(), "sk-x", "gpt-4o", true);
        var exchange = service.exchange(target, new ObjectMapper().createObjectNode());
        assertThat(exchange.status()).isEqualTo(500);
        assertThat(exchange.streamed()).isFalse();
        assertThat(exchange.jsonBody()).contains("boom");
    }
}
