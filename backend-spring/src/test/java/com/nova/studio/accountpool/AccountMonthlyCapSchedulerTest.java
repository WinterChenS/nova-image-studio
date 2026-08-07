package com.nova.studio.accountpool;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * T26 (WIN-29) — monthly cap scheduler: periodic sweep delegates to
 * {@link AccountMonthlyCapService#checkAllAccounts()}.
 */
class AccountMonthlyCapSchedulerTest {

    private final AccountMonthlyCapService service = mock(AccountMonthlyCapService.class);

    @Test
    void scheduledRunChecksAllAccounts() {
        AccountMonthlyCapScheduler scheduler = new AccountMonthlyCapScheduler(service);
        scheduler.check();
        verify(service).checkAllAccounts();
    }
}
