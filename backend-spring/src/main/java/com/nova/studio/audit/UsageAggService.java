package com.nova.studio.audit;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T25 (WIN-29) — usage daily aggregation service (A10): drives
 * {@link UsageAggRepository#upsertAggForDate} for the scheduled task and
 * exposes the user-scoped daily view used by "我的用量" (T24) — aggregates
 * survive detail-row retention cleanup.
 */
@Service
public class UsageAggService {

    private final UsageAggRepository repository;

    public UsageAggService(UsageAggRepository repository) {
        this.repository = repository;
    }

    /** Aggregate one date's detail rows into usage_daily_agg (idempotent upsert). */
    public int aggregate(LocalDate date) {
        return repository.upsertAggForDate(date);
    }

    /** User's daily aggregates (all types summed per date) for the /usage/me view. */
    public List<Map<String, Object>> userDaily(UUID userId, LocalDate from, LocalDate to) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (UsageAggRepository.AggRow row : repository.findByUserAndRange(userId, from, to)) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("date", row.aggDate().toString());
            dto.put("modelId", row.modelId() == null ? null : row.modelId().toString());
            dto.put("accountId", row.accountId() == null ? null : row.accountId().toString());
            dto.put("reqType", row.reqType());
            dto.put("requestCount", row.requestCount());
            dto.put("successCount", row.successCount());
            dto.put("inputTokens", row.inputTokens());
            dto.put("outputTokens", row.outputTokens());
            dto.put("cost", row.cost());
            dto.put("currency", row.currency());
            result.add(dto);
        }
        return result;
    }
}
