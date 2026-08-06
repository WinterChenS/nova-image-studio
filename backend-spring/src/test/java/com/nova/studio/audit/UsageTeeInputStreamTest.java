package com.nova.studio.audit;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T8 (WIN-28) — SSE side-channel usage parsing (F.2/H6/ADR-5): the tee passes
 * bytes through unchanged (A21 transferTo semantics) while parsing SSE events
 * on the side, keeping the last non-null usage; chunk boundaries and CRLF are
 * handled; parse failures yield usage=null without breaking the stream.
 */
class UsageTeeInputStreamTest {

    private String tee(byte[] input, AtomicReference<UsageParser.UsageTokens> usage,
                       AtomicReference<Throwable> error) throws Exception {
        UsageTeeInputStream tee = new UsageTeeInputStream(new ByteArrayInputStream(input),
                (u, e) -> {
                    usage.set(u);
                    error.set(e);
                });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        tee.transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void passthroughBytesAreUnchanged() throws Exception {
        AtomicReference<UsageParser.UsageTokens> usage = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n"
                + "data: [DONE]\n\n";
        String out = tee(sse.getBytes(StandardCharsets.UTF_8), usage, error);

        assertThat(out).isEqualTo(sse);   // 逐字节透传（A21）
        assertThat(usage.get()).isNotNull();
        assertThat(usage.get().inputTokens()).isEqualTo(3);
        assertThat(usage.get().outputTokens()).isEqualTo(2);
    }

    @Test
    void keepsLastNonNullUsage() throws Exception {
        AtomicReference<UsageParser.UsageTokens> usage = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        String sse = "data: {\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}\n\n"
                + "data: {\"usage\":{\"input_tokens\":99,\"output_tokens\":99}}\n\n";
        tee(sse.getBytes(StandardCharsets.UTF_8), usage, error);

        assertThat(usage.get().inputTokens()).isEqualTo(99);   // 最后 chunk 生效
    }

    @Test
    void fragmentedChunksStillParse() throws Exception {
        AtomicReference<UsageParser.UsageTokens> usage = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        String sse = "data: {\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":8}}\n\n";
        byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
        // 逐字节喂入（极端 chunk 边界）
        ByteArrayInputStream in = new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        };
        UsageTeeInputStream tee = new UsageTeeInputStream(in, (u, e) -> {
            usage.set(u);
            error.set(e);
        });
        tee.transferTo(ByteOutputStream());

        assertThat(usage.get()).isNotNull();
        assertThat(usage.get().inputTokens()).isEqualTo(7);
        assertThat(usage.get().outputTokens()).isEqualTo(8);
    }

    @Test
    void crlfSeparatedEventsParse() throws Exception {
        AtomicReference<UsageParser.UsageTokens> usage = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        String sse = "data: {\"usage\":{\"input_tokens\":2,\"output_tokens\":3}}\r\n\r\n";
        tee(sse.getBytes(StandardCharsets.UTF_8), usage, error);
        assertThat(usage.get()).isNotNull();
    }

    @Test
    void noUsageYieldsNull() throws Exception {
        AtomicReference<UsageParser.UsageTokens> usage = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        tee("data: {\"choices\":[{\"message\":{\"content\":\"hi\"}}]}\n\n".getBytes(StandardCharsets.UTF_8), usage, error);
        assertThat(usage.get()).isNull();
    }

    @Test
    void geminiSseUsageMetadataParsed() throws Exception {
        AtomicReference<UsageParser.UsageTokens> usage = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        String sse = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}\n\n"
                + "data: {\"usageMetadata\":{\"promptTokenCount\":11,\"candidatesTokenCount\":22}}\n\n";
        tee(sse.getBytes(StandardCharsets.UTF_8), usage, error);
        assertThat(usage.get().inputTokens()).isEqualTo(11);
        assertThat(usage.get().outputTokens()).isEqualTo(22);
    }

    private static ByteArrayOutputStream ByteOutputStream() {
        return new ByteArrayOutputStream();
    }
}
