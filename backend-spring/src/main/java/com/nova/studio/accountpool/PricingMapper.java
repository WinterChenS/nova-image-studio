package com.nova.studio.accountpool;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * WIN-28 (V5) — MyBatis-Plus mapper for {@code ai_model_pricing}. The write
 * path is a single upsert on UNIQUE(model_id, currency) so price edits never
 * create duplicate rows (A8: historical cost is snapshotted in
 * usage_records.cost, so update-in-place is safe).
 */
@Mapper
public interface PricingMapper extends BaseMapper<PricingEntity> {

    @Insert("""
            INSERT INTO ai_model_pricing
                (id, model_id, currency, per_request_price, price_per_token, effective_from,
                 created_by, created_at, updated_at)
            VALUES
                (#{id}, #{modelId}, #{currency}, #{perRequestPrice}, #{pricePerToken}, #{effectiveFrom},
                 #{createdBy}, #{createdAt}, #{updatedAt})
            ON CONFLICT (model_id, currency) DO UPDATE SET
                per_request_price = EXCLUDED.per_request_price,
                price_per_token   = EXCLUDED.price_per_token,
                effective_from    = EXCLUDED.effective_from,
                updated_at        = EXCLUDED.updated_at
            """)
    int upsert(@Param("id") UUID id, @Param("modelId") UUID modelId, @Param("currency") String currency,
               @Param("perRequestPrice") BigDecimal perRequestPrice, @Param("pricePerToken") BigDecimal pricePerToken,
               @Param("effectiveFrom") Instant effectiveFrom, @Param("createdBy") UUID createdBy,
               @Param("createdAt") Instant createdAt, @Param("updatedAt") Instant updatedAt);
}
