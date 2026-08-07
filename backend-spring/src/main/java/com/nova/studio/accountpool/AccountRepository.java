package com.nova.studio.accountpool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WIN-28 (V5) — {@code ai_accounts} access (T3). Status/health/scope writes
 * use explicit mapper SQL; reads use BaseMapper wrappers.
 */
@Repository
public class AccountRepository {

    /** Public row shape (service/test contract). */
    public record Row(UUID id, String name, String protocol, String baseUrl, String apiKeyEnc,
                      String modelScopeJson, String status, Integer priority, BigDecimal monthlyCapCost,
                      String healthJson, String remark, UUID createdBy,
                      Instant createdAt, Instant updatedAt) {
    }

    private final AccountMapper mapper;

    public AccountRepository(AccountMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<Row> findById(UUID id) {
        return Optional.ofNullable(mapper.selectById(id)).map(AccountRepository::toRow);
    }

    public List<Row> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<AccountEntity>()
                .orderByAsc(AccountEntity::getCreatedAt)).stream()
                .map(AccountRepository::toRow).toList();
    }

    public List<Row> findAllByStatus(String status) {
        return mapper.selectList(new LambdaQueryWrapper<AccountEntity>()
                .eq(AccountEntity::getStatus, status)
                .orderByAsc(AccountEntity::getCreatedAt)).stream()
                .map(AccountRepository::toRow).toList();
    }

    public UUID insert(String name, String protocol, String baseUrl, String apiKeyEnc,
                       String modelScopeJson, Integer priority, BigDecimal monthlyCapCost, String remark, UUID createdBy) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        AccountEntity e = new AccountEntity();
        e.setId(id);
        e.setName(name);
        e.setProtocol(protocol);
        e.setBaseUrl(baseUrl);
        e.setApiKeyEnc(apiKeyEnc);
        e.setModelScopeJson(modelScopeJson == null ? "[]" : modelScopeJson);
        e.setStatus("active");
        e.setPriority(priority == null ? 100 : priority);
        e.setMonthlyCapCost(monthlyCapCost);
        e.setHealthJson("{}");
        e.setRemark(remark);
        e.setCreatedBy(createdBy);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        mapper.insert(e);
        return id;
    }

    public int update(UUID id, String name, String protocol, String baseUrl, String apiKeyEnc,
                      String modelScopeJson, Integer priority, BigDecimal monthlyCapCost, String remark) {
        return mapper.updateRow(id, name, protocol, baseUrl, apiKeyEnc, modelScopeJson,
                priority, monthlyCapCost, remark, Instant.now());
    }

    public int updateStatus(UUID id, String status) {
        return mapper.updateStatus(id, status, Instant.now());
    }

    public int updateHealth(UUID id, String healthJson) {
        return mapper.updateHealth(id, healthJson, Instant.now());
    }

    public int updateScope(UUID id, String modelScopeJson) {
        return mapper.updateScope(id, modelScopeJson, Instant.now());
    }

    /** 当前自然月内该账号的累计费用（快照 cost 求和，T26 月度上限判定）。 */
    public BigDecimal monthlyCost(UUID accountId) {
        java.math.BigDecimal value = mapper.monthlyCost(accountId);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Row toRow(AccountEntity e) {
        return new Row(e.getId(), e.getName(), e.getProtocol(), e.getBaseUrl(), e.getApiKeyEnc(),
                e.getModelScopeJson(), e.getStatus(), e.getPriority(), e.getMonthlyCapCost(),
                e.getHealthJson(), e.getRemark(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
