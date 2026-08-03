package com.nova.studio.imagegen;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ImagePayloadExtractor} — port of the Node backend's
 * image payload extraction semantics (server.js {@code getImagePayloadValue},
 * {@code parseImageEventStream}, {@code extractImagePayloadFromEventStream}).
 */
class ImagePayloadExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void extractsB64FromDataArray() throws Exception {
        JsonNode json = mapper.readTree("""
                {"created":123,"data":[{"b64_json":"QUJD"}]}
                """);
        assertThat(ImagePayloadExtractor.extract(json)).isEqualTo("QUJD");
    }

    @Test
    void extractsUrlFromDataArray() throws Exception {
        JsonNode json = mapper.readTree("""
                {"data":[{"url":"https://example.com/a.png"}]}
                """);
        assertThat(ImagePayloadExtractor.extract(json)).isEqualTo("URL:https://example.com/a.png");
    }

    @Test
    void extractsNestedResultPath() throws Exception {
        JsonNode json = mapper.readTree("""
                {"result":{"response":{"output":{"image_url":"https://x.io/i.png"}}}}
                """);
        assertThat(ImagePayloadExtractor.extract(json)).isEqualTo("URL:https://x.io/i.png");
    }

    @Test
    void normalizesDataUriPrefix() throws Exception {
        JsonNode json = mapper.readTree("""
                {"data":[{"b64_json":"data:image/png;base64,QUJD"}]}
                """);
        assertThat(ImagePayloadExtractor.extract(json)).isEqualTo("QUJD");
    }

    @Test
    void throwsWhenNoImageData() {
        JsonNode json = mapper.createObjectNode().put("error", "boom");
        assertThatThrownBy(() -> ImagePayloadExtractor.extract(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("响应中无图片数据");
    }

    @Test
    void parsesSseStreamSkippingCommentsAndDone() throws Exception {
        String sse = """
                : ping
                data: {"type":"partial_image","b64_json":"cGFydA=="}

                data: {"object":"image","b64_json":"RklOQUw="}

                data: [DONE]

                """;
        var payloads = ImagePayloadExtractor.parseEventStream(sse, mapper);
        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0).get("type").asText()).isEqualTo("partial_image");
        assertThat(payloads.get(1).get("b64_json").asText()).isEqualTo("RklOQUw=");
    }

    @Test
    void extractsFinalImageFromEventStreamSkippingPartialEvents() throws Exception {
        String sse = """
                data: {"type":"partial_image","b64_json":"cDE="}

                data: {"type":"partial_image","b64_json":"cDI="}

                data: {"object":"image","b64_json":"RklOQUw="}

                data: [DONE]

                """;
        assertThat(ImagePayloadExtractor.extractFromEventStream(sse, mapper)).isEqualTo("RklOQUw=");
    }

    @Test
    void eventStreamWithOnlyErrorThrowsUpstreamMessage() {
        String sse = """
                data: {"type":"error","error":{"message":"stream not supported"}}

                data: [DONE]

                """;
        assertThatThrownBy(() -> ImagePayloadExtractor.extractFromEventStream(sse, mapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stream not supported");
    }

    @Test
    void eventStreamWithoutImageThrows() {
        String sse = """
                data: {"object":"list","data":[]}

                data: [DONE]

                """;
        assertThatThrownBy(() -> ImagePayloadExtractor.extractFromEventStream(sse, mapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("响应中无图片数据");
    }

    @Test
    void parsesCrlfLineEndings() throws Exception {
        String sse = "data: {\"b64_json\":\"QUJD\"}\r\n\r\ndata: [DONE]\r\n\r\n";
        assertThat(ImagePayloadExtractor.extractFromEventStream(sse, mapper)).isEqualTo("QUJD");
    }
}
