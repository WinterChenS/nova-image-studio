package com.nova.studio.accountpool;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * WIN-28 (V5) — MyBatis-Plus mapper for {@code ai_accounts}. Status/health
 * transitions keep explicit SQL so JSONB columns (model_scope/health) are
 * written exactly and cache invalidation stays deterministic.
 */
@Mapper
public interface AccountMapper extends BaseMapper<AccountEntity> {

    @Update("""
            UPDATE ai_accounts SET
                name = #{name}, protocol = #{protocol}, base_url = #{baseUrl},
                api_key_enc = #{apiKeyEnc}, model_scope = #{modelScopeJson}::jsonb,
                priority = #{priority}, monthly_cap_cost = #{monthlyCapCost},
                remark = #{remark}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateRow(@Param("id") UUID id, @Param("name") String name, @Param("protocol") String protocol,
                  @Param("baseUrl") String baseUrl, @Param("apiKeyEnc") String apiKeyEnc,
                  @Param("modelScopeJson") String modelScopeJson, @Param("priority") Integer priority,
                  @Param("monthlyCapCost") BigDecimal monthlyCapCost, @Param("remark") String remark,
                  @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE ai_accounts SET status = #{status}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateStatus(@Param("id") UUID id, @Param("status") String status, @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE ai_accounts SET health = #{healthJson}::jsonb, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateHealth(@Param("id") UUID id, @Param("healthJson") String healthJson, @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE ai_accounts SET model_scope = #{modelScopeJson}::jsonb, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateScope(@Param("id") UUID id, @Param("modelScopeJson") String modelScopeJson, @Param("updatedAt") Instant updatedAt);

    /** T26: 当前自然月该账号累计费用（快照 cost 求和）。 */
    @org.apache.ibatis.annotations.Select("""
            SELECT COALESCE(SUM(cost), 0) FROM usage_records
            WHERE account_id = #{accountId} AND created_at >= date_trunc('month', now())
            """)
    java.math.BigDecimal monthlyCost(@Param("accountId") UUID accountId);
}
