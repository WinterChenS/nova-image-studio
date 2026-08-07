package com.nova.studio.accountpool;

import com.nova.studio.audit.AuditLogService;
import com.nova.studio.infra.HttpErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T26 (WIN-29) — monthly cost cap (F-31): after a usage write the account's
 * current-month accumulated cost (snapshot cost summed from usage_records) is
 * compared against {@code monthly_cap_cost}; when it reaches the cap the
 * account is automatically {@code paused} (never deleted — ADR-28 semantics),
 * the transition is recorded in audit_log, and an admin later resumes it via
 * the existing {@link AccountService#resume} path. No cap configured → no-op.
 */
@Service
public class MonthlyCapService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyCapService.class);

    private final AccountRepository repository;
    private final AccountService accountService;
    private final AuditLogService auditLog;

    public MonthlyCapService(AccountRepository repository, AccountService accountService,
                             AuditLogService auditLog) {
        this.repository = repository;
        this.accountService = accountService;
        this.auditLog = auditLog;
    }

    /**
     * Enforce after one usage row is written. Only {@code active} accounts
     * with a non-null cap are checked; paused/broken/deleted are left alone.
     */
    public void enforceAfterUsage(java.util.UUID accountId) {
        if (accountId == null) {
            return;
        }
        AccountRepository.Row account = repository.findById(accountId).orElse(null);
        if (account == null || account.monthlyCapCost() == null
                || !AccountService.STATUS_ACTIVE.equals(account.status())) {
            return;
        }
        BigDecimal monthly = repository.monthlyCost(accountId);
        if (monthly == null || monthly.compareTo(account.monthlyCapCost()) < 0) {
            return;
        }
        log.warn("[monthly-cap] 账号 {} 本月费用 {} ≥ 上限 {}，自动暂停（不删除），需管理员解除",
                account.name(), monthly, account.monthlyCapCost());
        try {
            accountService.pause(accountId);
        } catch (HttpErrorException e) {
            // 竞态兜底：检查与暂停之间状态已变化（如 admin 已手动停用）——不重复暂停
            log.warn("[monthly-cap] 账号 {} 暂停失败（可能已变更状态）: {}", account.name(), e.getMessage());
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("monthlyCost", monthly);
        detail.put("monthlyCapCost", account.monthlyCapCost());
        detail.put("reason", "月度费用上限达限自动暂停");
        auditLog.record(null, "account.cap_paused", "ai_accounts", accountId.toString(), detail);
    }
}
