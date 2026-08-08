package com.nova.studio.audit;

import com.nova.studio.accountpool.AccountRepository;
import com.nova.studio.accountpool.CatalogModelRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T24 (WIN-29) — 我的用量（A11）：user self-service query <b>always scoped to
 * the caller's own userId</b> (the API never accepts a target user, so no
 * cross-user read exists). Summary + paged detail come from usage_records; the
 * daily trend comes from usage_daily_agg (T25) so it survives detail retention
 * cleanup. Names (model/account) are resolved for display only.
 */
@Service
public class UsageMeService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DAILY_LOOKBACK_DAYS = 30;

    private final UsageRecordRepository repository;
    private final UsageAggRepository aggRepository;
    private final CatalogModelRepository catalogRepository;
    private final AccountRepository accountRepository;

    public UsageMeService(UsageRecordRepository repository,
                          UsageAggRepository aggRepository,
                          CatalogModelRepository catalogRepository,
                          AccountRepository accountRepository) {
        this.repository = repository;
        this.aggRepository = aggRepository;
        this.catalogRepository = catalogRepository;
        this.accountRepository = accountRepository;
    }

    public Map<String, Object> myUsage(UUID userId, Instant from, Instant to, int page, int size) {
        int pageNum = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(size <= 0 ? DEFAULT_PAGE_SIZE : size, MAX_PAGE_SIZE));
        int offset = (pageNum - 1) * pageSize;

        List<UsageRecordRepository.Row> rows = repository.searchByUser(userId, from, to, offset, pageSize);
        long total = repository.countByUser(userId, from, to);

        Map<UUID, String> models = new HashMap<>();
        Map<UUID, String> accounts = new HashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (UsageRecordRepository.Row row : rows) {
            items.add(toItem(row, models, accounts));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", pageNum);
        result.put("size", pageSize);
        result.put("summary", repository.summaryByUser(userId, from, to));
        result.put("daily", dailyTrend(userId, from, to));
        return result;
    }

    private List<Map<String, Object>> dailyTrend(UUID userId, Instant from, Instant to) {
        ZoneId tz = ZoneId.of("Asia/Shanghai");
        LocalDate toDate = to == null ? LocalDate.now(tz) : to.atZone(tz).toLocalDate();
        LocalDate fromDate = from == null ? toDate.minusDays(DAILY_LOOKBACK_DAYS - 1) : from.atZone(tz).toLocalDate();
        List<Map<String, Object>> daily = new ArrayList<>();
        for (UsageAggRepository.DailyAggRow row : aggRepository.rowsForUser(userId, fromDate, toDate)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("aggDate", row.aggDate() == null ? null : row.aggDate().toString());
            d.put("reqType", row.reqType());
            d.put("requestCount", row.requestCount());
            d.put("successCount", row.successCount());
            d.put("inputTokens", row.inputTokens());
            d.put("outputTokens", row.outputTokens());
            d.put("cost", row.cost());
            d.put("currency", row.currency());
            daily.add(d);
        }
        return daily;
    }

    private Map<String, Object> toItem(UsageRecordRepository.Row row, Map<UUID, String> models,
                                       Map<UUID, String> accounts) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", row.id());
        dto.put("refId", row.refId());   // WIN-40 复测修复：与 admin 用量导出（AuditQueryService）一致，供前端对账
        dto.put("modelId", row.modelId() == null ? null : row.modelId().toString());
        dto.put("modelName", name(models, row.modelId(),
                id -> catalogRepository.findById(id).map(m -> m.name()).orElse("")));
        dto.put("accountId", row.accountId() == null ? null : row.accountId().toString());
        dto.put("accountName", name(accounts, row.accountId(),
                id -> accountRepository.findById(id).map(a -> a.name()).orElse("")));
        dto.put("protocol", row.protocol());
        dto.put("reqType", row.reqType());
        dto.put("refType", row.refType());
        dto.put("status", row.status());
        dto.put("inputTokens", row.inputTokens());
        dto.put("outputTokens", row.outputTokens());
        dto.put("images", row.images());
        dto.put("cost", row.cost());
        dto.put("currency", row.currency());
        dto.put("durationMs", row.durationMs());
        dto.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        return dto;
    }

    private String name(Map<UUID, String> cache, UUID id, java.util.function.Function<UUID, String> loader) {
        if (id == null) {
            return "";
        }
        return cache.computeIfAbsent(id, loader);
    }
}
