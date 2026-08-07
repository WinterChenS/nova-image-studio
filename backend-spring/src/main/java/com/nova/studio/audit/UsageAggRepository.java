package com.nova.studio.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * WIN-29 (V5) — {@code usage_daily_agg} access (T25/A10): the aggregation
 * <b>sums the snapshot {@code cost} column</b> of the detail rows (never
 * recomputes from pricing — price changes don't rewrite history, A8) and
 * upserts into the daily table whose PK
 * {@code (agg_date, user_id, model_id, account_id, req_type)} keeps it
 * independent of the retention cleanup (detail rows may be deleted,
 * aggregates remain).
 */
@Repository
public class UsageAggRepository {

    /** Daily aggregate row (user-scoped "我的用量" view, T24). */
    public record AggRow(LocalDate aggDate, UUID modelId, UUID accountId, String reqType,
                         int requestCount, int successCount, Long inputTokens, Long outputTokens,
                         BigDecimal cost, String currency) {
    }

    private final JdbcTemplate jdbcTemplate;

    public UsageAggRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Upsert one day's aggregates: SUM over usage_records grouped by
     * (date, user, model, account, req_type); cost = SUM(snapshot cost).
     */
    public int upsertAggForDate(LocalDate date) {
        return jdbcTemplate.update("""
                INSERT INTO usage_daily_agg
                    (agg_date, user_id, model_id, account_id, req_type,
                     request_count, success_count, input_tokens, output_tokens, cost, currency, updated_at)
                SELECT created_at::date, user_id, model_id, account_id, req_type,
                       COUNT(*),
                       COUNT(*) FILTER (WHERE status IN ('success', 'retried')),
                       COALESCE(SUM(input_tokens), 0),
                       COALESCE(SUM(output_tokens), 0),
                       COALESCE(SUM(cost), 0),
                       MIN(currency), now()
                FROM usage_records
                WHERE created_at::date = ? AND user_id IS NOT NULL
                GROUP BY created_at::date, user_id, model_id, account_id, req_type
                ON CONFLICT (agg_date, user_id, model_id, account_id, req_type) DO UPDATE SET
                    request_count = EXCLUDED.request_count,
                    success_count = EXCLUDED.success_count,
                    input_tokens  = EXCLUDED.input_tokens,
                    output_tokens = EXCLUDED.output_tokens,
                    cost          = EXCLUDED.cost,
                    currency      = EXCLUDED.currency,
                    updated_at    = now()
                """, Date.valueOf(date));
    }

    /** User's daily aggregates within [from, to] (inclusive), newest first. */
    public List<AggRow> findByUserAndRange(UUID userId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                SELECT agg_date, model_id, account_id, req_type, request_count, success_count,
                       input_tokens, output_tokens, cost, currency
                FROM usage_daily_agg
                WHERE user_id = ? AND agg_date BETWEEN ? AND ?
                ORDER BY agg_date DESC
                """, (rs, i) -> new AggRow(
                rs.getDate("agg_date").toLocalDate(),
                nullableUuid(rs.getObject("model_id")),
                nullableUuid(rs.getObject("account_id")),
                rs.getString("req_type"),
                rs.getInt("request_count"),
                rs.getInt("success_count"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getBigDecimal("cost"),
                rs.getString("currency")), userId, Date.valueOf(from), Date.valueOf(to));
    }

    private static UUID nullableUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
