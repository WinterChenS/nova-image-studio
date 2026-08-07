package com.nova.studio.audit;

import com.nova.studio.accountpool.AccountRepository;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.auth.UserRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * T10 (WIN-28) — audit query service: filtered paginated detail + summary
 * cards (A9) and CSV export (current filters, ≤100k rows; P2 async). Names
 * (user/model/account) are resolved for display; usage rows never carry keys.
 */
@Service
public class AuditQueryService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final UsageRecordRepository repository;
    private final UserRepository userRepository;
    private final CatalogModelRepository catalogRepository;
    private final AccountRepository accountRepository;

    public AuditQueryService(UsageRecordRepository repository,
                             UserRepository userRepository,
                             CatalogModelRepository catalogRepository,
                             AccountRepository accountRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.catalogRepository = catalogRepository;
        this.accountRepository = accountRepository;
    }

    public Map<String, Object> query(UsageRecordRepository.Filters filters, int page, int size) {
        int pageNum = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(size <= 0 ? DEFAULT_PAGE_SIZE : size, MAX_PAGE_SIZE));
        int offset = (pageNum - 1) * pageSize;
        List<UsageRecordRepository.Row> rows = repository.search(filters, offset, pageSize);
        long total = repository.count(filters);

        Map<UUID, String> users = new HashMap<>();
        Map<UUID, String> models = new HashMap<>();
        Map<UUID, String> accounts = new HashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (UsageRecordRepository.Row row : rows) {
            items.add(toDto(row, users, models, accounts));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", pageNum);
        result.put("size", pageSize);
        result.put("summary", repository.summary(filters));
        return result;
    }

    /** RFC-4180 CSV export (UTF-8 with BOM so Excel renders Chinese). */
    public String exportCsv(UsageRecordRepository.Filters filters) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');   // BOM
        sb.append("时间,用户,用户ID,模型,模型ID,账号,账号ID,协议,类型,输入tokens,输出tokens,图数,费用,币种,状态,耗时ms,引用类型,引用ID\n");
        Map<UUID, String> users = new HashMap<>();
        Map<UUID, String> models = new HashMap<>();
        Map<UUID, String> accounts = new HashMap<>();
        for (UsageRecordRepository.Row row : repository.export(filters)) {
            sb.append(esc(row.createdAt() == null ? "" : row.createdAt().toString())).append(',');
            sb.append(esc(name(users, row.userId(), id -> userRepository.findById(id).map(u -> u.username()).orElse("")))).append(',');
            sb.append(esc(row.userId() == null ? "" : row.userId().toString())).append(',');
            sb.append(esc(name(models, row.modelId(), id -> catalogRepository.findById(id).map(m -> m.name()).orElse("")))).append(',');
            sb.append(esc(row.modelId() == null ? "" : row.modelId().toString())).append(',');
            sb.append(esc(name(accounts, row.accountId(), id -> accountRepository.findById(id).map(a -> a.name()).orElse("")))).append(',');
            sb.append(esc(row.accountId() == null ? "" : row.accountId().toString())).append(',');
            sb.append(esc(row.protocol())).append(',');
            sb.append(esc(row.reqType())).append(',');
            sb.append(row.inputTokens() == null ? "" : row.inputTokens()).append(',');
            sb.append(row.outputTokens() == null ? "" : row.outputTokens()).append(',');
            sb.append(row.images() == null ? "" : row.images()).append(',');
            sb.append(row.cost() == null ? "" : row.cost().toPlainString()).append(',');
            sb.append(esc(row.currency())).append(',');
            sb.append(esc(row.status())).append(',');
            sb.append(row.durationMs() == null ? "" : row.durationMs()).append(',');
            sb.append(esc(row.refType())).append(',');
            sb.append(esc(row.refId())).append('\n');
        }
        return sb.toString();
    }

    private Map<String, Object> toDto(UsageRecordRepository.Row row, Map<UUID, String> users,
                                      Map<UUID, String> models, Map<UUID, String> accounts) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", row.id());
        dto.put("userId", row.userId() == null ? null : row.userId().toString());
        dto.put("username", name(users, row.userId(), id -> userRepository.findById(id).map(u -> u.username()).orElse("")));
        dto.put("modelId", row.modelId() == null ? null : row.modelId().toString());
        dto.put("modelName", name(models, row.modelId(), id -> catalogRepository.findById(id).map(m -> m.name()).orElse("")));
        dto.put("accountId", row.accountId() == null ? null : row.accountId().toString());
        dto.put("accountName", name(accounts, row.accountId(), id -> accountRepository.findById(id).map(a -> a.name()).orElse("")));
        dto.put("protocol", row.protocol());
        dto.put("reqType", row.reqType());
        dto.put("refType", row.refType());
        dto.put("refId", row.refId());
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

    private static String esc(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
