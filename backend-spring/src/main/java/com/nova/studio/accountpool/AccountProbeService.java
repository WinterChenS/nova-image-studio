package com.nova.studio.accountpool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * T27 (WIN-29) — 主动健康探测：周期对 <b>active 且空闲</b> 的账号做轻量探活
 * （复用 {@link AccountService#probeUpstream} 的 /models 或最小请求），配合
 * 被动失败计数（H4）。探测对象 = 未在冷却中 + （从未成功 或 上次成功超过
 * 间隔）；成功 → {@code recordSuccess}（清失败计数），失败 →
 * {@code recordFailure}（按分类冷却，连续达阈值触发 broken，A22）。
 */
@Service
public class AccountProbeService {

    private static final Logger log = LoggerFactory.getLogger(AccountProbeService.class);

    private final AccountService accountService;
    private final AccountHealthService healthService;
    private final ObjectMapper objectMapper;
    private final Duration idleInterval;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AccountProbeService(AccountService accountService,
                               AccountHealthService healthService,
                               ObjectMapper objectMapper,
                               @Value("${NOVA_ACCOUNT_PROBE_INTERVAL_MS:600000}") long intervalMs) {
        this(accountService, healthService, objectMapper, intervalMs, Clock.systemDefaultZone());
    }

    /** Test constructor with a fixed clock. */
    AccountProbeService(AccountService accountService, AccountHealthService healthService,
                        ObjectMapper objectMapper, long intervalMs, Clock clock) {
        this.accountService = accountService;
        this.healthService = healthService;
        this.objectMapper = objectMapper;
        this.idleInterval = Duration.ofMillis(Math.max(60_000, intervalMs));
        this.clock = clock;
    }

    /** Probe every active account that is due (idle beyond interval, not cooling). */
    public void probeDueAccounts() {
        for (AccountRepository.Row account : accountService.activeAccounts()) {
            try {
                if (!needsProbe(account)) {
                    continue;
                }
                AccountService.ProbeResult result = accountService.probeUpstream(account);
                if (result.ok()) {
                    healthService.recordSuccess(account);
                } else {
                    AccountHealthService.ErrorKind kind =
                            healthService.classify(new RuntimeException(result.message()));
                    healthService.recordFailure(account, kind, result.message());
                }
            } catch (Exception e) {
                log.warn("[probe] 账号 {} 探测异常: {}", account.name(), e.getMessage());
            }
        }
    }

    /** Probe eligibility: not cooling down + (never succeeded OR idle beyond interval). */
    boolean needsProbe(AccountRepository.Row account) {
        if (healthService.isCoolingDown(account)) {
            return false;
        }
        AccountHealth health = AccountHealth.parse(account.healthJson(), objectMapper);
        Instant lastSuccess = health.lastSuccessAt();
        if (lastSuccess == null) {
            return true;
        }
        return lastSuccess.isBefore(Instant.now(clock).minus(idleInterval));
    }
}
