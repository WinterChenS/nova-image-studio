package com.nova.studio.accountpool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * T4 (WIN-28) — account health state machine (ADR-25/E.3): consecutive
 * failures with per-kind cooldown, threshold → {@code broken} (persisted,
 * manual recovery only, A4/A22), success resets. Memory-first + persisted to
 * {@code ai_accounts.health} JSONB (restart restores cooldown; broken lives in
 * the row status).
 */
@Service
public class AccountHealthService {

    private static final Logger log = LoggerFactory.getLogger(AccountHealthService.class);

    /** Failure classification (E.3): cooldown + retriability per kind. */
    public enum ErrorKind {
        RATE_LIMITED(Duration.ofSeconds(60), true),    // 429 → 短冷却，可换账号
        SERVER_ERROR(Duration.ofSeconds(300), true),   // 5xx/超时/连接 → 中冷却，可换账号
        UNAUTHORIZED(Duration.ofSeconds(600), false),  // 401 → Key 失效嫌疑，不重试
        REJECTED(Duration.ofSeconds(600), false);      // 其他 4xx → 确定性拒绝，不重试

        private final Duration cooldown;
        private final boolean retriable;

        ErrorKind(Duration cooldown, boolean retriable) {
            this.cooldown = cooldown;
            this.retriable = retriable;
        }

        public Duration cooldown() {
            return cooldown;
        }

        /** R3: 可重试类（换账号重试 ≤2）；401/4xx 不换账号。 */
        public boolean retriable() {
            return retriable;
        }
    }

    private static final Pattern SERVER_STATUS = Pattern.compile("\\b(5\\d\\d)\\b");
    private static final Pattern CLIENT_STATUS = Pattern.compile("\\b(4\\d\\d)\\b");
    private static final Pattern NETWORK_OR_TIMEOUT = Pattern.compile(
            "(?i)failed to fetch|fetch failed|networkerror|network request failed|econnreset|socket hang up|"
                    + "connect timed out|connection refused|read timed out|abort|timeout|timed out|超时|网络连接失败");

    private final AccountService accountService;
    private final ObjectMapper objectMapper;
    private final int brokenThreshold;
    private final Map<UUID, AccountHealth> memory = new ConcurrentHashMap<>();

    public AccountHealthService(AccountService accountService,
                                ObjectMapper objectMapper,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${nova.account.broken-threshold:3}") int brokenThreshold) {
        this.accountService = accountService;
        this.objectMapper = objectMapper;
        this.brokenThreshold = Math.max(1, brokenThreshold);
    }

    /** Cooldown check for the scheduler — memory first, persisted health fallback (L3). */
    public boolean isCoolingDown(AccountRepository.Row account) {
        return state(account).coolingDown();
    }

    /**
     * Records a failure: bump consecutive count, set cooldown per kind,
     * persist health; at threshold → {@code broken} (DB status, no auto
     * recovery — A22). The last error is truncated (never a full key/stack).
     */
    public void recordFailure(AccountRepository.Row account, ErrorKind kind, String message) {
        AccountHealth current = state(account);
        int failures = current.consecutiveFailures() + 1;
        AccountHealth updated = new AccountHealth(failures, Instant.now().plus(kind.cooldown),
                truncate(message), current.lastSuccessAt());
        memory.put(account.id(), updated);
        if (failures >= brokenThreshold) {
            log.warn("[account-health] 账号 {} 连续失败 {} 次（≥{}），标记 broken，需人工恢复",
                    account.name(), failures, brokenThreshold);
            accountService.markBroken(account.id());
            return;
        }
        accountService.updateHealth(account.id(), updated);
    }

    /** Single success resets failures/cooldown and stamps last_success_at. */
    public void recordSuccess(AccountRepository.Row account) {
        AccountHealth ok = new AccountHealth(0, null, null, Instant.now());
        memory.put(account.id(), ok);
        accountService.updateHealth(account.id(), ok);
    }

    /** E.3 failure classification from an exception message. */
    public ErrorKind classify(Throwable error) {
        String msg = error == null ? "" : String.valueOf(error.getMessage());
        if (msg.contains("401")) {
            return ErrorKind.UNAUTHORIZED;
        }
        if (msg.contains("429")) {
            return ErrorKind.RATE_LIMITED;
        }
        if (SERVER_STATUS.matcher(msg).find() || NETWORK_OR_TIMEOUT.matcher(msg).find()) {
            return ErrorKind.SERVER_ERROR;
        }
        if (CLIENT_STATUS.matcher(msg).find()) {
            return ErrorKind.REJECTED;
        }
        return ErrorKind.REJECTED;
    }

    private AccountHealth state(AccountRepository.Row account) {
        return memory.getOrDefault(account.id(),
                AccountHealth.parse(account.healthJson(), objectMapper));
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }
}
