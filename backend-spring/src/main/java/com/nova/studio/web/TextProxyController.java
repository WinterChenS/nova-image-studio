package com.nova.studio.web;

import com.nova.studio.accountpool.AccountHealthService;
import com.nova.studio.accountpool.AccountScheduler;
import com.nova.studio.accountpool.AccountService;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.accountpool.CatalogModelService;
import com.nova.studio.audit.UsageCollector;
import com.nova.studio.audit.UsageParser;
import com.nova.studio.audit.UsageTeeInputStream;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.infra.NormalizedError;
import com.nova.studio.textproxy.TextProxyService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Text proxy endpoint (T1.5 + M2 T2.4 + WIN-28 T6) — {@code POST
 * /api/nova/proxy/text}. WIN-28: the body's {@code modelId} is a catalog UUID;
 * the account is selected from the pool at request time by
 * {@link AccountScheduler}, with the R3 retry boundary (ADR-24): non-streaming
 * requests may retry with another account ≤2 times; SSE requests switch
 * accounts only before the first byte reaches the client (once the upstream
 * stream is 2xx + headers written, never switch — A21).
 *
 * <p>SSE passthrough keeps the exact upstream status/headers/body (same as
 * before); usage collection hooks (WIN-28 T8) wrap the stream transparently.
 */
@RestController
@RequestMapping("/api/nova/proxy/text")
public class TextProxyController {

    private static final Pattern TIMEOUT_PATTERN = Pattern.compile("(?i)abort|timeout");

    /** R3: 非流式完整重试 ≤2 次（最多 3 次尝试）。 */
    private static final int MAX_ATTEMPTS = 3;

    private final TextProxyService textProxyService;
    private final CatalogModelService catalogModelService;
    private final AccountScheduler accountScheduler;
    private final AccountHealthService accountHealthService;
    private final AccountService accountService;
    private final UsageCollector usageCollector;
    private final long requestTimeoutMs;
    private final ObjectMapper objectMapper;

    public TextProxyController(TextProxyService textProxyService,
                               CatalogModelService catalogModelService,
                               AccountScheduler accountScheduler,
                               AccountHealthService accountHealthService,
                               AccountService accountService,
                               UsageCollector usageCollector,
                               @Value("${nova.task.request-timeout-ms:1800000}") long requestTimeoutMs,
                               ObjectMapper objectMapper) {
        this.textProxyService = textProxyService;
        this.catalogModelService = catalogModelService;
        this.accountScheduler = accountScheduler;
        this.accountHealthService = accountHealthService;
        this.accountService = accountService;
        this.usageCollector = usageCollector;
        this.requestTimeoutMs = requestTimeoutMs;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public void proxy(@RequestBody(required = false) JsonNode body, @AuthenticationPrincipal AuthUser authUser,
                      HttpServletResponse response) throws IOException {
        AuthUser user = AuthSupport.requireAuth(authUser);   // M2 T13 门禁收口前控制器先兜底 401
        String modelIdField = text(body, "modelId");
        if (modelIdField == null) {
            writeJson(response, 400, Map.of("error", "Missing modelId"));
            return;
        }
        CatalogModelRepository.Row model = resolveCatalogModel(modelIdField, response);
        if (model == null) {
            return;
        }
        boolean stream = body != null && body.has("stream") && body.get("stream").asBoolean(false);
        JsonNode forwarded = TextProxyService.forwardedBody(body);
        String refId = UUID.randomUUID().toString();   // H5: 代理请求 UUID（usage ref_id）
        long startedAt = System.currentTimeMillis();

        Set<UUID> tried = new HashSet<>();
        int attempts = 0;
        while (true) {
            AccountScheduler.SelectedAccount selected;
            try {
                selected = accountScheduler.select(model, tried);
            } catch (HttpErrorException e) {
                writeJson(response, e.getStatusCode(), Map.of("error", e.getMessage()));
                return;
            }
            tried.add(selected.accountId());
            attempts++;
            try {
                TextProxyService.Target target = textProxyService.buildTarget(
                        selected.protocol(), selected.baseUrl(), selected.apiKey(), model.modelId(), stream);
                TextProxyService.ProxyExchange exchange = textProxyService.exchange(target, forwarded);
                if (exchange.streamed()) {
                    // SSE：连接建立（2xx + 流）后绝不换账号（R3/A21）
                    response.setStatus(exchange.status());
                    response.setContentType("text/event-stream");
                    response.setCharacterEncoding("UTF-8");
                    response.setHeader("Cache-Control", "no-cache");
                    response.setHeader("Connection", "keep-alive");
                    response.setHeader("X-Accel-Buffering", "no");
                    // T8: 旁路 tee 解析最后 chunk 的 usage（逐字节透传，H6）
                    final boolean retried = attempts > 1;
                    UsageTeeInputStream tee = new UsageTeeInputStream(exchange.stream(), (tokens, err) -> {
                        if (err == null) {
                            recordProxyUsage(selected, model, user, refId, retried, false,
                                    tokens == null ? null : tokens.inputTokens(),
                                    tokens == null ? null : tokens.outputTokens(), startedAt);
                        }
                    });
                    tee.transferTo(response.getOutputStream());
                    return;
                }
                if (exchange.status() >= 200 && exchange.status() < 300) {
                    recordSuccess(selected);
                    // T8: 非流式完整 body 解析 usage（失败 usage=null，H6）
                    UsageParser.UsageTokens tokens = parseBodyUsage(exchange.jsonBody());
                    recordProxyUsage(selected, model, user, refId, attempts > 1, false,
                            tokens == null ? null : tokens.inputTokens(),
                            tokens == null ? null : tokens.outputTokens(), startedAt);
                    writeJson(response, exchange.status(), parseJsonOrRaw(exchange.jsonBody(), exchange.status()));
                    return;
                }
                // 非流式上游非 2xx：可重试类（429/5xx）换账号重试，其余直接转发
                AccountHealthService.ErrorKind kind = classifyStatus(exchange.status());
                recordFailure(selected, kind, "上游返回 " + exchange.status());
                if (kind.retriable() && attempts < MAX_ATTEMPTS) {
                    continue;
                }
                recordProxyUsage(selected, model, user, refId, attempts > 1, true, null, null, startedAt);
                writeJson(response, exchange.status(), parseJsonOrRaw(exchange.jsonBody(), exchange.status()));
                return;
            } catch (Exception e) {
                // 连接建立前失败（超时/连接错误）→ 可换账号重试；否则 502/504
                AccountHealthService.ErrorKind kind = accountHealthService.classify(e);
                recordFailure(selected, kind, e.getMessage());
                if (kind.retriable() && attempts < MAX_ATTEMPTS) {
                    continue;
                }
                recordProxyUsage(selected, model, user, refId, attempts > 1, true, null, null, startedAt);
                if (e.getMessage() != null && TIMEOUT_PATTERN.matcher(e.getMessage()).find()) {
                    writeJson(response, 504, Map.of("error", "代理请求上游超时"));
                } else {
                    writeJson(response, 502, Map.of("error", NormalizedError.normalize(e, requestTimeoutMs)));
                }
                return;
            } finally {
                accountScheduler.release(selected.accountId());   // E.4: 流结束/非流式完成 → 在飞 --
            }
        }
    }

    private void recordProxyUsage(AccountScheduler.SelectedAccount selected, CatalogModelRepository.Row model,
                                  AuthUser user, String refId, boolean retried, boolean failed,
                                  Long inputTokens, Long outputTokens, long startedAt) {
        usageCollector.recordProxyUsage(new UsageCollector.ProxyUsage(
                refId, user.id(), model.id(), selected.accountId(), selected.protocol(),
                retried, failed, inputTokens, outputTokens, System.currentTimeMillis() - startedAt));
    }

    private UsageParser.UsageTokens parseBodyUsage(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) {
            return null;
        }
        try {
            return UsageParser.parseUsage(objectMapper.readTree(bodyText));
        } catch (Exception e) {
            return null;   // H6: 解析失败 usage=null，业务零影响
        }
    }

    private CatalogModelRepository.Row resolveCatalogModel(String modelIdField, HttpServletResponse response)
            throws IOException {
        UUID catalogId;
        try {
            catalogId = UUID.fromString(modelIdField.trim());
        } catch (IllegalArgumentException e) {
            writeJson(response, 400, Map.of("error", "未找到文本模型配置"));
            return null;
        }
        CatalogModelRepository.Row model = catalogModelService.resolve(catalogId).orElse(null);
        if (model == null) {
            writeJson(response, 400, Map.of("error", "未找到文本模型配置"));
            return null;
        }
        if (!"text".equals(model.type())) {
            writeJson(response, 400, Map.of("error", "模型不是文本模型"));
            return null;
        }
        if (model.enabled() == null || !model.enabled()) {
            writeJson(response, 400, Map.of("error", "模型已禁用，请联系管理员"));
            return null;
        }
        return model;
    }

    private void recordSuccess(AccountScheduler.SelectedAccount selected) {
        accountService.findById(selected.accountId()).ifPresent(accountHealthService::recordSuccess);
    }

    private void recordFailure(AccountScheduler.SelectedAccount selected, AccountHealthService.ErrorKind kind, String message) {
        accountService.findById(selected.accountId())
                .ifPresent(row -> accountHealthService.recordFailure(row, kind, message));
    }

    private AccountHealthService.ErrorKind classifyStatus(int status) {
        if (status == 401) {
            return AccountHealthService.ErrorKind.UNAUTHORIZED;
        }
        if (status == 429) {
            return AccountHealthService.ErrorKind.RATE_LIMITED;
        }
        if (status >= 500) {
            return AccountHealthService.ErrorKind.SERVER_ERROR;
        }
        return AccountHealthService.ErrorKind.REJECTED;
    }

    private Object parseJsonOrRaw(String bodyText, int status) {
        if (bodyText == null || bodyText.isBlank()) {
            return Map.of("error", "上游返回 " + status);
        }
        try {
            return objectMapper.readValue(bodyText, Object.class);
        } catch (Exception e) {
            return Map.of("error", "上游返回 " + status);
        }
    }

    private void writeJson(HttpServletResponse response, int status, Object payload) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(payload));
    }

    private static String text(JsonNode body, String key) {
        if (body == null || !body.has(key) || !body.get(key).isTextual()) {
            return null;
        }
        return body.get(key).asText();
    }
}
