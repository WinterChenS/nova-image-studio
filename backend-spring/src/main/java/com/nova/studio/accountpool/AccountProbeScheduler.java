package com.nova.studio.accountpool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * T27 (WIN-29) — active health probe (H4): periodically runs the lightweight
 * upstream probe ({@link AccountService#probe}, the same /models shape as the
 * connectivity test) over every {@code active} account and feeds the results
 * into the <b>passive</b> health state machine (success resets failures,
 * failure bumps the consecutive counter / cooldown — ADR-25). This gives idle
 * accounts an active liveness signal without waiting for traffic.
 *
 * <p>Disabled in tests via {@code nova.account-probe.enabled: false}
 * (src/test/resources/application.yml) so integration tests never hit real
 * upstreams.
 */
@Component
@ConditionalOnProperty(name = "nova.account-probe.enabled", havingValue = "true", matchIfMissing = true)
public class AccountProbeScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountProbeScheduler.class);

    private final AccountService accountService;
    private final AccountHealthService healthService;

    public AccountProbeScheduler(AccountService accountService, AccountHealthService healthService) {
        this.accountService = accountService;
        this.healthService = healthService;
    }

    /** 每 10 分钟探测一次 active 账号（可经 NOVA_ACCOUNT_PROBE_INTERVAL_MS 调整）。 */
    @Scheduled(fixedDelayString = "${NOVA_ACCOUNT_PROBE_INTERVAL_MS:600000}")
    public void probeAll() {
        List<AccountRepository.Row> accounts = accountService.listRows();
        int probed = 0;
        for (AccountRepository.Row account : accounts) {
            if (!AccountService.STATUS_ACTIVE.equals(account.status())) {
                continue;   // 仅探测 active（broken/paused/deleted 跳过）
            }
            probed++;
            try {
                Map<String, Object> result = accountService.probe(account);
                boolean ok = Boolean.TRUE.equals(result.get("ok"));
                if (ok) {
                    healthService.recordSuccess(account);
                } else {
                    String message = String.valueOf(result.getOrDefault("message", "探活失败"));
                    healthService.recordFailure(account, AccountHealthService.ErrorKind.SERVER_ERROR, message);
                }
            } catch (Exception e) {
                healthService.recordFailure(account, AccountHealthService.ErrorKind.SERVER_ERROR,
                        String.valueOf(e.getMessage()));
            }
        }
        if (probed > 0) {
            log.debug("[account-probe] 探活完成：active 账号 {} 个", probed);
        }
    }
}
