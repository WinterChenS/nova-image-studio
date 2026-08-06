package com.nova.studio.accountpool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * WIN-28 — typed view of {@code ai_accounts.health} JSONB
 * ({@code consecutive_failures / cooldown_until / last_error / last_success_at},
 * ARCH E.1). Parsing failures degrade to an empty state (never throw in the
 * scheduling hot path).
 */
public record AccountHealth(int consecutiveFailures, Instant cooldownUntil,
                            String lastError, Instant lastSuccessAt) {

    public static final AccountHealth EMPTY = new AccountHealth(0, null, null, null);

    public static AccountHealth parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        try {
            JsonNode node = mapper.readTree(json);
            int failures = node.path("consecutive_failures").asInt(0);
            Instant cooldown = node.hasNonNull("cooldown_until")
                    ? Instant.parse(node.get("cooldown_until").asText()) : null;
            String lastError = node.hasNonNull("last_error") ? node.get("last_error").asText() : null;
            Instant lastSuccess = node.hasNonNull("last_success_at")
                    ? Instant.parse(node.get("last_success_at").asText()) : null;
            return new AccountHealth(failures, cooldown, lastError, lastSuccess);
        } catch (Exception e) {
            return EMPTY;
        }
    }

    public String toJson(ObjectMapper mapper) {
        try {
            var node = mapper.createObjectNode();
            node.put("consecutive_failures", consecutiveFailures);
            if (cooldownUntil != null) {
                node.put("cooldown_until", cooldownUntil.toString());
            }
            if (lastError != null) {
                node.put("last_error", lastError);
            }
            if (lastSuccessAt != null) {
                node.put("last_success_at", lastSuccessAt.toString());
            }
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    public boolean coolingDown() {
        return cooldownUntil != null && cooldownUntil.isAfter(Instant.now());
    }
}
