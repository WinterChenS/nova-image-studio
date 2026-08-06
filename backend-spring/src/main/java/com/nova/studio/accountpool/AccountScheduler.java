package com.nova.studio.accountpool;

import com.nova.studio.infra.HttpErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T4 (WIN-28) — account scheduler (ADR-23/24/34): least in-flight +
 * round-robin tie-break + health-cooldown exclusion, JVM in-memory
 * (single-instance assumption H13/R6; multi-instance needs distributed
 * coordination, P2 evolution).
 *
 * <p>Candidate set = active accounts with matching protocol (ADR-34 protocol
 * cross-check), model_scope empty or containing the model id (R1), cooldown
 * expired, and not in the retry exclusion set. Selection order =
 * {@code (inFlight ASC, lastSeq ASC)}; picking bumps {@code lastSeq} from a
 * global sequence so equal-in-flight accounts round-robin. In-flight is
 * acquired on selection and released by the caller in {@code finally}
 * (E.4 lifecycle).
 */
@Service
public class AccountScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountScheduler.class);

    private final AccountService accountService;
    private final AccountHealthService healthService;
    private final Map<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSeq = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public AccountScheduler(AccountService accountService, AccountHealthService healthService) {
        this.accountService = accountService;
        this.healthService = healthService;
    }

    /** Selected account with the decrypted key (never serialized). */
    public record SelectedAccount(UUID accountId, String name, String protocol, String baseUrl, String apiKey) {
    }

    /**
     * Picks the next account for {@code model} excluding already-tried ids
     * (retry). Throws 503 when no usable account exists (H1/A5).
     */
    public SelectedAccount select(CatalogModelRepository.Row model, Set<UUID> excluded) {
        List<AccountRepository.Row> candidates = new ArrayList<>();
        for (AccountRepository.Row account : accountService.activeAccounts()) {
            if (excluded != null && excluded.contains(account.id())) {
                continue;
            }
            if (!accountService.isCandidate(account, model)) {
                continue;
            }
            if (healthService.isCoolingDown(account)) {
                continue;   // 冷却剔除
            }
            candidates.add(account);
        }
        if (candidates.isEmpty()) {
            log.warn("[scheduler] 模型 {} 无可调度账号（excluded={}）", model.modelId(), excluded);
            throw new HttpErrorException(503, "NO_AVAILABLE_ACCOUNT", "该模型暂无可用账号，请联系管理员");
        }
        candidates.sort(Comparator
                .comparingInt((AccountRepository.Row a) -> inFlightOf(a.id()))
                .thenComparingLong(a -> lastSeq.getOrDefault(a.id(), 0L)));
        AccountRepository.Row pick = candidates.getFirst();
        lastSeq.put(pick.id(), sequence.incrementAndGet());
        acquire(pick.id());
        return new SelectedAccount(pick.id(), pick.name(), pick.protocol(), pick.baseUrl(),
                accountService.decryptKey(pick));
    }

    /** E.4: in-flight++ at dispatch/selection. */
    public void acquire(UUID accountId) {
        inFlight.computeIfAbsent(accountId, k -> new AtomicInteger()).incrementAndGet();
    }

    /** E.4: in-flight-- in finally (also on retry handover: old -1, new +1). */
    public void release(UUID accountId) {
        AtomicInteger counter = inFlight.get(accountId);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    public int inFlightOf(UUID accountId) {
        AtomicInteger counter = inFlight.get(accountId);
        return counter == null ? 0 : counter.get();
    }
}
