package com.nova.studio.imagegen;

import tools.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T0.3 — custom WebClient for the OpenAI {@code partial_images} streaming endpoint.
 *
 * <p>Verifies: (1) SSE streaming response parsed, partial events skipped, final
 * image extracted; (2) non-streaming JSON fallback path; (3) error propagation;
 * (4) request body carries {@code stream:true} + {@code partial_images} and the
 * OpenAI-compatible headers.
 */
class PartialImagesClientTest {

    private MockWebServer server;
    private PartialImagesClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        server = new MockWebServer();
        client = new PartialImagesClient(server.url("/").toString(), "test-key",
                WebClient.builder(), mapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void streamingSseResponseExtractsFinalImageAndSkipsPartialEvents() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"type":"partial_image","b64_json":"cDE="}

                        data: {"type":"partial_image","b64_json":"cDI="}

                        data: {"object":"image","b64_json":"RklOQUw="}

                        data: [DONE]

                        """));

        String image = client.generate(new PartialImagesClient.Request(
                "gpt-image-1", "a cat", 1, 3, "1024x1024", "high", "vivid", null, null, null));

        assertThat(image).isEqualTo("RklOQUw=");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/images/generations");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(req.getHeader("Content-Type")).contains("application/json");
        var body = mapper.readTree(req.getBody().readUtf8());
        assertThat(body.get("model").asText()).isEqualTo("gpt-image-1");
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.get("partial_images").asInt()).isEqualTo(3);
        assertThat(body.get("size").asText()).isEqualTo("1024x1024");
        assertThat(body.get("quality").asText()).isEqualTo("high");
        assertThat(body.get("output_format").asText()).isEqualTo("png");
    }

    @Test
    void nonStreamingJsonFallbackPath() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"created":1,"data":[{"b64_json":"Tk9OU1RSRUFN"}]}
                        """));

        String image = client.generate(new PartialImagesClient.Request(
                "gpt-image-1", "a cat", 1, 3, null, null, null, null, null, null));

        assertThat(image).isEqualTo("Tk9OU1RSRUFN");
    }

    @Test
    void upstreamErrorPropagates() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"error":{"message":"stream not supported"}}
                        """));
        assertThatThrownBy(() -> client.generate(new PartialImagesClient.Request(
                "gpt-image-1", "a cat", 1, 3, null, null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stream not supported");
    }

    @Test
    void requestWithoutPartialImagesOmitsStreamFlag() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[{\"b64_json\":\"QUJD\"}]}"));

        client.generate(new PartialImagesClient.Request(
                "gpt-image-1", "a cat", 1, null, null, null, null, null, null, null));

        RecordedRequest req = server.takeRequest();
        var body = mapper.readTree(req.getBody().readUtf8());
        assertThat(body.has("stream")).isFalse();
        assertThat(body.has("partial_images")).isFalse();
    }
}
