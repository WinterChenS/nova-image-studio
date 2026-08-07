package com.nova.studio.accountpool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T26 (WIN-29) — 月度费用上限检查调度：周期扫描（默认每 5 分钟，
 * {@code NOVA_MONTHLY_CAP_CHECK_INTERVAL_MS} 可配）达限账号并自动 paused。
 * 间隔可配以权衡 DB 扫描成本与「达限自动暂停」的及时性。
 */
@Component
public class AccountMonthlyCapScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountMonthlyCapScheduler.class);

    private final AccountMonthlyCapService service;

    public AccountMonthlyCapScheduler(AccountMonthlyCapService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${NOVA_MONTHLY_CAP_CHECK_INTERVAL_MS:300000}",
            fixedDelayString = "${NOVA_MONTHLY_CAP_CHECK_INTERVAL_MS:300000}")
    public void check() {
        try {
            service.checkAllAccounts();
        } catch (Exception e) {
            log.warn("[monthly-cap] 周期检查失败（下轮重试）: {}", e.getMessage());
        }
    }
}
