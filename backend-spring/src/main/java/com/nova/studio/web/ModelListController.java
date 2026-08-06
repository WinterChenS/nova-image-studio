package com.nova.studio.web;

import com.nova.studio.accountpool.AccountScheduler;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.accountpool.CatalogModelService;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.imagegen.ImageGenService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.infra.NormalizedError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Model list proxy (T1.6 + WIN-28 F-05/ADR-32) — {@code GET
 * /api/nova/proxy/models}. The frontend passes {@code modelId} (catalog UUID);
 * the server selects an account from the pool and queries the upstream model
 * list with that account's baseUrl/apiKey/protocol (NewAPI compatible).
 * Legacy baseUrl/apiKey query params are removed (Q1 直接移除).
 */
@RestController
@RequestMapping("/api/nova/proxy/models")
public class ModelListController {

    private final WebClient.Builder webClientBuilder;
    private final CatalogModelService catalogModelService;
    private final AccountScheduler accountScheduler;
    private final long requestTimeoutMs;

    public ModelListController(WebClient.Builder webClientBuilder,
                               CatalogModelService catalogModelService,
                               AccountScheduler accountScheduler,
                               @Value("${nova.task.request-timeout-ms:1800000}") long requestTimeoutMs) {
        this.webClientBuilder = webClientBuilder;
        this.catalogModelService = catalogModelService;
        this.accountScheduler = accountScheduler;
        this.requestTimeoutMs = requestTimeoutMs;
    }

    @GetMapping
    public ResponseEntity<?> models(@RequestParam(required = false) String modelId,
                                    @AuthenticationPrincipal AuthUser authUser) {
        AuthUser user = AuthSupport.requireAuth(authUser);
        if (modelId == null || modelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing modelId"));
        }
        CatalogModelRepository.Row model;
        try {
            model = catalogModelService.resolve(UUID.fromString(modelId.trim())).orElse(null);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "未找到模型配置"));
        }
        if (model == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "未找到模型配置"));
        }
        AccountScheduler.SelectedAccount selected;
        try {
            selected = accountScheduler.select(model, Set.of());
        } catch (HttpErrorException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getMessage()));
        }
        try {
            String normalizedBaseUrl = ImageGenService.normalizeProtocolBaseUrl(selected.protocol(), selected.baseUrl());
            Map<String, String> headers = new LinkedHashMap<>();
            String modelsUrl;
            if ("google".equals(selected.protocol()) || "google-gemini".equals(selected.protocol())) {
                modelsUrl = normalizedBaseUrl + "/v1beta/models";
                headers.put("x-goog-api-key", selected.apiKey());
                headers.put("Authorization", "Bearer " + selected.apiKey());
            } else {
                modelsUrl = normalizedBaseUrl + "/v1/models";
                headers.put("Authorization", "Bearer " + selected.apiKey());
                if ("anthropic-messages".equals(selected.protocol())) {
                    headers.put("x-api-key", selected.apiKey());
                    headers.put("anthropic-version", "2023-06-01");
                }
            }

            record StatusBody(int status, String text) {
            }
            StatusBody result = webClientBuilder.build().get()
                    .uri(modelsUrl)
                    .headers(h -> headers.forEach(h::set))
                    .exchangeToMono(resp -> resp.bodyToMono(String.class)
                            .map(text -> new StatusBody(resp.statusCode().value(), text)))
                    .block();
            if (result == null) {
                return ResponseEntity.status(502).body(Map.of("error", "上游无响应"));
            }
            Object payload = parseJsonSafely(result.text());
            return ResponseEntity.status(result.status()).body(payload != null ? payload : Map.of("error", "上游返回 " + result.status()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", NormalizedError.normalize(e, requestTimeoutMs)));
        } finally {
            accountScheduler.release(selected.accountId());
        }
    }

    private static Object parseJsonSafely(String text) {
        try {
            return new tools.jackson.databind.ObjectMapper().readValue(text, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
