package com.nova.studio.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * T25 (WIN-29) — {@code usage_daily_agg} aggregation access. Each day is
 * <b>rebuilt</b> (DELETE + INSERT..SELECT) so runs are idempotent even when
 * late usage rows arrive; {@code cost} is the SUM of the detail <b>snapshot</b>
 * cost (不重算，A10/F-33) and the PK is
 * {@code (agg_date, user_id, model_id, account_id, req_type)}. Aggregation is
 * grouped on {@code (created_at AT TIME ZONE :tz)::date} so the "day" follows
 * a configured timezone (default Asia/Shanghai). Detail retention cleanup never
 * touches this table — aggregates survive detail deletion.
 */
@Repository
public class UsageAggRepository {

    private static final Logger log = LoggerFactory.getLogger(UsageAggRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public UsageAggRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Rebuild one day's aggregates from usage_records. Idempotent: previous
     * rows for the day are removed first, then re-derived from the detail
     * table (cost = SUM(snapshot cost), no recompute).
     *
     * @return number of aggregate rows written for the day
     */
    public int aggregateDay(LocalDate day, String timezone) {
        String tz = timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone;
        LocalDate dayEnd = day.plusDays(1);
        int deleted = jdbcTemplate.update("DELETE FROM usage_daily_agg WHERE agg_date = ?",
                java.sql.Date.valueOf(day));
        int inserted = jdbcTemplate.update("""
                INSERT INTO usage_daily_agg
                    (agg_date, user_id, model_id, account_id, req_type,
                     request_count, success_count, input_tokens, output_tokens, cost, currency, updated_at)
                SELECT (created_at AT TIME ZONE ?)::date AS agg_date,
                       user_id, model_id, account_id, req_type,
                       COUNT(*) AS request_count,
                       COUNT(*) FILTER (WHERE status IN ('success', 'retried')) AS success_count,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(cost), 0) AS cost,
                       MIN(currency) AS currency,
                       now() AS updated_at
                FROM usage_records
                WHERE (created_at AT TIME ZONE ?)::date = ?
                GROUP BY agg_date, user_id, model_id, account_id, req_type
                """, tz, tz, day);
        if (inserted > 0 || deleted > 0) {
            log.info("[daily-agg] {} 聚合完成：删除 {} 行旧值，写入 {} 行（tz={}）", day, deleted, inserted, tz);
        }
        return inserted;
    }

    /** Aggregate rows for a user over a date range (我的用量日趋势, T24). */
    public List<DailyAggRow> rowsForUser(java.util.UUID userId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                SELECT agg_date, user_id, model_id, account_id, req_type,
                       request_count, success_count, input_tokens, output_tokens, cost, currency
                FROM usage_daily_agg
                WHERE user_id = ? AND agg_date >= ? AND agg_date <= ?
                ORDER BY agg_date ASC
                """, (rs, i) -> new DailyAggRow(
                rs.getObject("agg_date") == null ? null : rs.getDate("agg_date").toLocalDate(),
                rs.getObject("user_id") == null ? null : java.util.UUID.fromString(String.valueOf(rs.getObject("user_id"))),
                rs.getObject("model_id") == null ? null : java.util.UUID.fromString(String.valueOf(rs.getObject("model_id"))),
                rs.getObject("account_id") == null ? null : java.util.UUID.fromString(String.valueOf(rs.getObject("account_id"))),
                rs.getString("req_type"),
                rs.getInt("request_count"),
                rs.getInt("success_count"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getBigDecimal("cost"),
                rs.getString("currency")), userId, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
    }

    /** Public row shape (usage/me daily trend). */
    public record DailyAggRow(LocalDate aggDate, java.util.UUID userId, java.util.UUID modelId,
                              java.util.UUID accountId, String reqType, int requestCount, int successCount,
                              long inputTokens, long outputTokens, java.math.BigDecimal cost, String currency) {
    }
}
