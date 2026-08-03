package com.nova.studio.imagegen;

import tools.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T0.2/T0.3 — Gemini image via custom WebClient.
 *
 * <p>Spike finding: released Spring AI 2.0.0 GA has no {@code GoogleGenAiImageModel},
 * so the Gemini image path is verified as a custom REST call replicating the Node
 * backend's {@code generateNovaGeminiImage}. This test proves the wire protocol:
 * {@code POST /v1beta/models/{model}:generateContent} with
 * {@code generationConfig.imageConfig{imageSize, aspectRatio}} and
 * {@code responseModalities:["IMAGE"]}, plus response extraction.
 */
class GeminiImageClientTest {

    private MockWebServer server;
    private GeminiImageClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        server = new MockWebServer();
        client = new GeminiImageClient(server.url("/").toString(), "google-key",
                WebClient.builder(), mapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsImageConfigWithImageSizeAndAspectRatio() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"candidates":[{"content":{"parts":[{"inlineData":{"data":"QUJD","mimeType":"image/png"}}]}}]}
                        """));

        String image = client.generate(new GeminiImageClient.Request(
                "gemini-2.0-flash-exp-image-generation", "a red fox",
                List.of(new GeminiImageClient.InlineImage("cmVm", "image/png")),
                1.0, "1024x1024", "1:1"));

        assertThat(image).isEqualTo("QUJD");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1beta/models/gemini-2.0-flash-exp-image-generation:generateContent");
        assertThat(req.getHeader("x-goog-api-key")).isEqualTo("google-key");
        var body = mapper.readTree(req.getBody().readUtf8());
        var generationConfig = body.get("generationConfig");
        assertThat(generationConfig.get("responseModalities").get(0).asText()).isEqualTo("IMAGE");
        assertThat(generationConfig.get("imageConfig").get("imageSize").asText()).isEqualTo("1024x1024");
        assertThat(generationConfig.get("imageConfig").get("aspectRatio").asText()).isEqualTo("1:1");
        var parts = body.get("contents").get(0).get("parts");
        assertThat(parts.get(0).get("text").asText()).isEqualTo("a red fox");
        assertThat(parts.get(1).get("inlineData").get("data").asText()).isEqualTo("cmVm");
    }

    @Test
    void acceptsInlineDataSnakeCaseVariant() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"candidates":[{"content":{"parts":[{"inline_data":{"data":"WFla"}}]}}]}
                        """));

        String image = client.generate(new GeminiImageClient.Request(
                "gemini-2.0-flash-exp-image-generation", "a cat", List.of(), null, null, null));

        assertThat(image).isEqualTo("WFla");
    }

    @Test
    void omitsImageConfigWhenSizeAndRatioAbsent() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"candidates":[{"content":{"parts":[{"inlineData":{"data":"QUJD"}}]}}]}
                        """));

        client.generate(new GeminiImageClient.Request(
                "gemini-2.0-flash-exp-image-generation", "a cat", List.of(), null, null, null));

        RecordedRequest req = server.takeRequest();
        var body = mapper.readTree(req.getBody().readUtf8());
        var imageConfig = body.get("generationConfig").get("imageConfig");
        assertThat(imageConfig.has("imageSize")).isFalse();
        assertThat(imageConfig.has("aspectRatio")).isFalse();
    }

    @Test
    void throwsWhenNoImageInResponse() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"nope\"}]}}]}"));

        assertThatThrownBy(() -> client.generate(new GeminiImageClient.Request(
                "gemini-2.0-flash-exp-image-generation", "a cat", List.of(), null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("响应中无图片数据");
    }
}
