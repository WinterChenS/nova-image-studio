package com.nova.studio.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * T8 (WIN-28) — SSE usage side-channel tee (F.2/H6): wraps the upstream
 * {@link InputStream} and passes every byte through untouched (A21: transferTo
 * semantics / SSE headers unchanged) while parsing SSE events on the side.
 * The <b>last non-null</b> usage wins (final chunk carries the totals for
 * OpenAI streaming). On stream end the {@code onFinish} consumer receives the
 * parsed usage (or null — parse failure never breaks the passthrough).
 *
 * <p>Implementation: a rolling byte buffer accumulates event payloads between
 * {@code \n\n} / {@code \r\n\r\n} separators; each {@code data:} line is
 * accumulated and parsed as JSON. Fragment boundaries are irrelevant because
 * the buffer only ever holds complete lines/events.
 */
public class UsageTeeInputStream extends FilterInputStream {

    private static final Logger log = LoggerFactory.getLogger(UsageTeeInputStream.class);

    private final ObjectMapper objectMapper;
    private final BiConsumer<UsageParser.UsageTokens, Throwable> onFinish;
    private final StringBuilder eventBuffer = new StringBuilder();
    private UsageParser.UsageTokens lastUsage;
    private boolean finished;

    public UsageTeeInputStream(InputStream in,
                               BiConsumer<UsageParser.UsageTokens, Throwable> onFinish) {
        super(in);
        this.objectMapper = new ObjectMapper();
        this.onFinish = onFinish;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            feed((byte) b);
        } else {
            finish(null);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            for (int i = 0; i < n; i++) {
                feed(b[off + i]);
            }
        } else {
            finish(null);
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        finish(null);
        super.close();
    }

    /** Consumes one byte for side-channel parsing (never modifies the stream). */
    private void feed(byte b) {
        eventBuffer.append((char) (b & 0xFF));
        // 事件分隔符：\n\n 或 \r\n\r\n
        String buf = eventBuffer.toString();
        if (buf.endsWith("\n\n") || buf.endsWith("\r\n\r\n")) {
            parseEvent(buf);
            eventBuffer.setLength(0);
        }
    }

    private void parseEvent(String rawEvent) {
        String data = extractData(rawEvent);
        if (data == null || data.isBlank()) {
            return;
        }
        try {
            var node = objectMapper.readTree(data);
            UsageParser.UsageTokens tokens = UsageParser.parseUsage(node);
            if (tokens != null) {
                lastUsage = tokens;   // 最后 chunk 生效（F.3）
            }
        } catch (Exception e) {
            // 解析失败仅 usage=null（H6），不阻断透传
            log.debug("[usage-tee] SSE data 解析失败: {}", e.getMessage());
        }
    }

    private static String extractData(String rawEvent) {
        StringBuilder data = new StringBuilder();
        for (String line : rawEvent.split("\n")) {
            String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (l.startsWith("data:")) {
                String value = l.substring(5).trim();
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(value);
            }
        }
        return data.toString();
    }

    private void finish(Throwable error) {
        if (finished) {
            return;
        }
        finished = true;
        // 尾部残块（无分隔符）也尝试解析一次
        if (!eventBuffer.isEmpty()) {
            parseEvent(eventBuffer.toString());
            eventBuffer.setLength(0);
        }
        try {
            onFinish.accept(lastUsage, error);
        } catch (Exception e) {
            log.warn("[usage-tee] onFinish 回调异常: {}", e.getMessage());
        }
    }
}
