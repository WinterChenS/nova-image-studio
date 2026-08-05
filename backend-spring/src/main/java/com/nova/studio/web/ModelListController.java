package com.nova.studio.web;

import com.nova.studio.auth.AuthFilter;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.imagegen.ImageGenService;
import com.nova.studio.infra.NormalizedError;
import com.nova.studio.settings.ModelService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Model list proxy (T1.6 + M2 T2.4) — {@code GET /api/nova/proxy/models}. Port of the
 * Node handler: per-protocol target URL + auth headers (google → /v1beta/models
 * with x-goog-api-key; anthropic → /v1/models with x-api-key; else /v1/models
 * with Bearer), upstream status + body forwarded verbatim (NewAPI compatible).
 *
 * <p>M2: the frontend may pass {@code modelId} (registry UUID) instead of
 * apiKey/baseUrl — the server resolves the config from the user's models and
 * the plaintext key never reaches the client. Legacy baseUrl/apiKey query
 * params stay supported.
 */
@RestController
@RequestMapping("/api/nova/proxy/models")
public class ModelListController {

    private final WebClient.Builder webClientBuilder;
    private final ModelService modelService;
    private final long requestTimeoutMs;

    public ModelListController(WebClient.Builder webClientBuilder,
                               ModelService modelService,
                               @Value("${nova.task.request-timeout-ms:1800000}") long requestTimeoutMs) {
        this.webClientBuilder = webClientBuilder;
        this.modelService = modelService;
        this.requestTimeoutMs = requestTimeoutMs;
    }

    @GetMapping
    public ResponseEntity<?> models(@RequestParam(required = false) String baseUrl,
                                    @RequestParam(required = false) String apiKey,
                                    @RequestParam(required = false) String modelId,
                                    @RequestParam(defaultValue = "openai") String protocol,
                                    HttpServletRequest request) {
        // M2 resolution path: modelId → server-side config.
        AuthUser authUser = AuthFilter.current(request);
        if (modelId != null && authUser != null) {
            ModelService.ResolvedModel resolved = modelService.resolve(authUser.id(), modelId).orElse(null);
            if (resolved == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "未找到模型配置"));
            }
            if (resolved.apiKey() == null || resolved.apiKey().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "模型配置不完整，请先在设置中填写 API Key"));
            }
            baseUrl = resolved.baseUrl();
            apiKey = resolved.apiKey();
            protocol = resolved.protocol();
        }
        if (baseUrl == null || apiKey == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing baseUrl or apiKey"));
        }
        try {
            String normalizedBaseUrl = ImageGenService.normalizeProtocolBaseUrl(protocol, baseUrl);
            Map<String, String> headers = new LinkedHashMap<>();
            String modelsUrl;
            if ("google".equals(protocol) || "google-gemini".equals(protocol)) {
                modelsUrl = normalizedBaseUrl + "/v1beta/models";
                headers.put("x-goog-api-key", apiKey);
                headers.put("Authorization", "Bearer " + apiKey);
            } else if ("anthropic-messages".equals(protocol)) {
                modelsUrl = normalizedBaseUrl + "/v1/models";
                headers.put("x-api-key", apiKey);
                headers.put("anthropic-version", "2023-06-01");
            } else {
                modelsUrl = normalizedBaseUrl + "/v1/models";
                headers.put("Authorization", "Bearer " + apiKey);
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
