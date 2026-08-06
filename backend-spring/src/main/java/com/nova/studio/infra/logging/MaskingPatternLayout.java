package com.nova.studio.infra.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * T3.2 (WIN-13) — logback layout that redacts secrets from every log line
 * before it reaches the appender (F-17 / PRD §7 安全「日志无 Key」/ ARCH C.8
 * 「结构化日志（脱敏）」). Defense-in-depth on top of the "never log keys"
 * code audit: even a future debug log that accidentally includes a request
 * body, header dump or exception chain can't leak a key.
 *
 * <p>Registered as the encoder layout via {@code logback-spring.xml}
 * ({@code MaskingPatternLayoutEncoder}); keeps Spring Boot's standard
 * {@code CONSOLE_LOG_PATTERN} untouched — only the rendered message is masked.
 */
public class MaskingPatternLayout extends PatternLayout {

    /** OpenAI/Anthropic/Gemini style API keys (sk-..., sk-proj-..., AIza...). */
    private static final Pattern SK_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{12,}");

    /** Gemini-style API keys (AIza...). */
    private static final Pattern AIZA_KEY = Pattern.compile("AIza[0-9A-Za-z_\\-]{20,}");

    /** JWT-like tokens (three dot-separated base64url segments). */
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]{2,}");

    /** {@code Authorization: Bearer <token>} / {@code bearer <token>}. */
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{12,}");

    /** {@code api_key/apiKey/apikey} or {@code "apiKey"} JSON field values. */
    private static final Pattern API_KEY_FIELD = Pattern.compile("(?i)([\"']?api[_-]?key[\"']?\\s*[:=]\\s*[\"']?)[^\\s\",}]{8,}");

    @Override
    public String doLayout(ILoggingEvent event) {
        return mask(super.doLayout(event));
    }

    /** Redacts every known secret shape; safe for arbitrary log text. */
    static String mask(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        String out = SK_KEY.matcher(line).replaceAll("sk-***");
        out = AIZA_KEY.matcher(out).replaceAll("AIza***");
        // Bearer first: full token (incl. JWT-shaped) becomes `Bearer ***` before the
        // JWT pattern can shorten it to a partial redaction.
        out = BEARER.matcher(out).replaceAll("$1***");
        out = API_KEY_FIELD.matcher(out).replaceAll("$1***");
        out = JWT.matcher(out).replaceAll("eyJ***");
        return out;
    }
}
