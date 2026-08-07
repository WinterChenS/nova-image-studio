package com.nova.studio.accountpool;

import com.nova.studio.audit.AuditLogService;
import com.nova.studio.audit.UsageRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * T26 (WIN-29) — 账号月度费用上限：当 {@code ai_accounts.monthly_cap_cost} 配置
 * 且当月费用（usage_records.cost 快照求和，自然月）达到上限时，自动
 * {@code status='paused'}（不删除账号）；管理员通过 resume 解除后恢复调度。
 * 周期任务触发 {@link #checkAllAccounts()}；未配置上限 / 非 active 账号跳过。
 * 达限自动暂停落 audit_log（actor=null 系统动作）。
 */
@Service
public class AccountMonthlyCapService {

    private static final Logger log = LoggerFactory.getLogger(AccountMonthlyCapService.class);

    private static final ZoneId TZ = ZoneId.of("Asia/Shanghai");

    private final AccountRepository accountRepository;
    private final UsageRecordRepository usageRepository;
    private final AuditLogService auditLogService;

    public AccountMonthlyCapService(AccountRepository accountRepository,
                                    UsageRecordRepository usageRepository,
                                    AuditLogService auditLogService) {
        this.accountRepository = accountRepository;
        this.usageRepository = usageRepository;
        this.auditLogService = auditLogService;
    }

    /** Sweep all accounts: auto-pause those whose current-month cost reached the cap. */
    public void checkAllAccounts() {
        for (AccountRepository.Row account : accountRepository.listAll()) {
            if (account.monthlyCapCost() == null) {
                continue;
            }
            try {
                checkAndPause(account.id());
            } catch (Exception e) {
                log.warn("[monthly-cap] 账号 {} 上限检查失败: {}", account.name(), e.getMessage());
            }
        }
    }

    /**
     * Check one account: pause when active + cap set + current-month cost ≥ cap.
     *
     * @return true when the account was auto-paused
     */
    public boolean checkAndPause(UUID accountId) {
        AccountRepository.Row account = accountRepository.findById(accountId).orElse(null);
        if (account == null || account.monthlyCapCost() == null
                || account.monthlyCapCost().signum() <= 0) {
            return false;
        }
        if (!AccountService.STATUS_ACTIVE.equals(account.status())) {
            return false;   // paused/broken/deleted 不再重复触发
        }
        BigDecimal cost = monthCost(accountId, YearMonth.now(TZ));
        if (cost.compareTo(account.monthlyCapCost()) < 0) {
            return false;
        }
        log.warn("[monthly-cap] 账号 {} 当月费用 {} 达到上限 {}，自动暂停（不删除，管理员可解除）",
                account.name(), cost, account.monthlyCapCost());
        accountRepository.updateStatus(accountId, AccountService.STATUS_PAUSED);
        auditLogService.log(null, "account.cap-paused", "ai_accounts", accountId.toString(),
                Map.of("name", account.name(), "monthlyCapCost", account.monthlyCapCost(), "monthCost", cost));
        return true;
    }

    /** Current calendar-month cost for an account (snapshot cost sum, no recompute). */
    public BigDecimal monthCost(UUID accountId, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(TZ).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(TZ).toInstant();
        return usageRepository.sumCostByAccount(accountId, from, to);
    }
}
