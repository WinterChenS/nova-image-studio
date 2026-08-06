package com.nova.studio.infra.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.PatternLayoutBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T3.2 (WIN-13) — {@link MaskingPatternLayout} redacts AI API keys, Bearer
 * tokens and JWT-like payloads from every log line (F-17 / ARCH C.8「日志脱敏」,
 * PRD §7 安全「日志无 Key」). Plain {@code %msg} layout keeps the test focused
 * on the regex redaction, not on logback pattern machinery.
 */
class MaskingPatternLayoutTest {

    private MaskingPatternLayout layout;

    @BeforeEach
    void setUp() {
        layout = new MaskingPatternLayout();
        layout.setPattern("%msg%n");
        layout.setContext(new ch.qos.logback.classic.LoggerContext());
        layout.start();
    }

    private String format(String message) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn(message);
        when(event.getThrowableProxy()).thenReturn(null);
        when(event.getMarkerList()).thenReturn(null);
        when(event.getKeyValuePairs()).thenReturn(List.of());
        return layout.doLayout(event);
    }

    @Test
    void masksOpenAiStyleKeys() {
        String masked = format("POST /v1/images/generations apiKey=sk-proj-AbCdEf1234567890XYZ end");
        assertThat(masked).contains("sk-***").doesNotContain("sk-proj-AbCdEf1234567890XYZ");
    }

    @Test
    void masksBareSkKeysInProse() {
        String masked = format("调用上游失败 sk-abc123def456ghi789 key=sk-abc123def456ghi789");
        assertThat(masked).doesNotContain("sk-abc123def456ghi789");
        assertThat(masked).contains("sk-***");
    }

    @Test
    void masksBearerTokens() {
        String masked = format("request Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig");
        assertThat(masked).doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig");
        assertThat(masked).contains("Bearer ***");
    }

    @Test
    void masksApiKeyFieldValues() {
        String masked = format("{\"api_key\":\"super-secret-value-123\",\"other\":1}");
        assertThat(masked).doesNotContain("super-secret-value-123");
        assertThat(masked).contains("api_key\":\"***\"");
    }

    @Test
    void masksJwtLikeTokensInProse() {
        String masked = format("token=eyJ0eXAiOiJKV1QifQ.eyJzdWIiOiIxIn0.sig-value-x");
        assertThat(masked).doesNotContain("eyJ0eXAiOiJKV1QifQ");
    }

    @Test
    void leavesNormalMessagesUntouched() {
        String message = "[gallery] prompts 种子导入完成: 3 条 (file=prompts.json)";
        assertThat(format(message)).isEqualTo(message + System.lineSeparator());
    }
}
