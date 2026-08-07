package com.nova.studio.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * WIN-28 (V5) — MyBatis-Plus mapper for {@code usage_records}. The write path
 * is the idempotent {@code INSERT ... ON CONFLICT (ref_type, ref_id) DO
 * NOTHING} (R2/ADR-26/A20 — 换账号重试不重复计费); audit queries go through
 * {@link UsageRecordRepository} (JdbcTemplate, dynamic filters).
 */
@Mapper
public interface UsageRecordMapper extends BaseMapper<UsageRecordEntity> {

    @Insert("""
            INSERT INTO usage_records
                (user_id, account_id, model_id, protocol, req_type, ref_type, ref_id, status,
                 input_tokens, output_tokens, images, cost, currency, duration_ms, created_at)
            VALUES
                (#{userId}, #{accountId}, #{modelId}, #{protocol}, #{reqType}, #{refType}, #{refId}, #{status},
                 #{inputTokens}, #{outputTokens}, #{images}, #{cost}, #{currency}, #{durationMs}, #{createdAt})
            ON CONFLICT (ref_type, ref_id) DO NOTHING
            """)
    int insertIgnore(@Param("userId") UUID userId, @Param("accountId") UUID accountId,
                     @Param("modelId") UUID modelId, @Param("protocol") String protocol,
                     @Param("reqType") String reqType, @Param("refType") String refType,
                     @Param("refId") String refId, @Param("status") String status,
                     @Param("inputTokens") Long inputTokens, @Param("outputTokens") Long outputTokens,
                     @Param("images") Integer images, @Param("cost") BigDecimal cost,
                     @Param("currency") String currency, @Param("durationMs") Long durationMs,
                     @Param("createdAt") Instant createdAt);
}
