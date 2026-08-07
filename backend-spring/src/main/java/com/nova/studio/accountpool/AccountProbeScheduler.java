package com.nova.studio.accountpool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T27 (WIN-29) — 主动健康探测调度：按 {@code NOVA_ACCOUNT_PROBE_INTERVAL_MS}
 * （默认 10 分钟）周期调用 {@link AccountProbeService#probeDueAccounts()}，
 * 配合被动失败计数（H4：被动为主、主动补探空闲账号）。
 */
@Component
public class AccountProbeScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountProbeScheduler.class);

    private final AccountProbeService service;

    public AccountProbeScheduler(AccountProbeService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${NOVA_ACCOUNT_PROBE_INTERVAL_MS:600000}",
            fixedDelayString = "${NOVA_ACCOUNT_PROBE_INTERVAL_MS:600000}")
    public void probe() {
        try {
            service.probeDueAccounts();
        } catch (Exception e) {
            log.warn("[probe] 主动健康探测失败（下轮重试）: {}", e.getMessage());
        }
    }
}
