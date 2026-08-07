package com.nova.studio.accountpool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WIN-28 (V5) — {@code ai_model_pricing} access (T9). Writes go through the
 * UNIQUE(model_id, currency) upsert; reads via BaseMapper wrappers.
 */
@Repository
public class PricingRepository {

    /** Public row shape (service/test contract). */
    public record Row(UUID id, UUID modelId, String currency,
                      BigDecimal perRequestPrice, BigDecimal pricePerToken,
                      Instant effectiveFrom, UUID createdBy, Instant createdAt, Instant updatedAt) {
    }

    private final PricingMapper mapper;

    public PricingRepository(PricingMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<Row> findByModelAndCurrency(UUID modelId, String currency) {
        PricingEntity e = mapper.selectOne(new LambdaQueryWrapper<PricingEntity>()
                .eq(PricingEntity::getModelId, modelId)
                .eq(PricingEntity::getCurrency, currency));
        return Optional.ofNullable(e).map(PricingRepository::toRow);
    }

    public List<Row> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<PricingEntity>()
                .orderByAsc(PricingEntity::getCreatedAt)).stream()
                .map(PricingRepository::toRow).toList();
    }

    public List<Row> findByModel(UUID modelId) {
        return mapper.selectList(new LambdaQueryWrapper<PricingEntity>()
                .eq(PricingEntity::getModelId, modelId)).stream()
                .map(PricingRepository::toRow).toList();
    }

    public int upsert(UUID modelId, String currency, BigDecimal perRequestPrice, BigDecimal pricePerToken,
                      UUID createdBy) {
        Instant now = Instant.now();
        return mapper.upsert(UUID.randomUUID(), modelId, currency, perRequestPrice, pricePerToken,
                null, createdBy, now, now);
    }

    public int deleteByModelAndCurrency(UUID modelId, String currency) {
        return mapper.delete(new LambdaQueryWrapper<PricingEntity>()
                .eq(PricingEntity::getModelId, modelId)
                .eq(PricingEntity::getCurrency, currency));
    }

    public int deleteByModel(UUID modelId) {
        return mapper.delete(new LambdaQueryWrapper<PricingEntity>()
                .eq(PricingEntity::getModelId, modelId));
    }

    private static Row toRow(PricingEntity e) {
        return new Row(e.getId(), e.getModelId(), e.getCurrency(),
                e.getPerRequestPrice(), e.getPricePerToken(),
                e.getEffectiveFrom(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
