package com.nova.studio.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WIN-28 (V5) — audit query access over {@code usage_records} (T10): dynamic
 * filter + paged search, summary aggregates, CSV export source (≤100k) and
 * retention cleanup (T11). JdbcTemplate keeps the dynamic WHERE readable;
 * writes go through {@link UsageRecordMapper#insertIgnore}.
 */
@Repository
public class UsageRecordRepository {

    private static final Logger log = LoggerFactory.getLogger(UsageRecordRepository.class);

    /** Public row shape for the audit API. */
    public record Row(Long id, UUID userId, UUID accountId, UUID modelId, String protocol,
                      String reqType, String refType, String refId, String status,
                      Long inputTokens, Long outputTokens, Integer images, BigDecimal cost,
                      String currency, Long durationMs, Instant createdAt) {
    }

    /** Audit filters (all optional). */
    public record Filters(Instant from, Instant to, UUID userId, UUID modelId, UUID accountId,
                          String protocol, String reqType, String status) {
    }

    public static final int EXPORT_LIMIT = 100_000;

    private final JdbcTemplate jdbcTemplate;

    public UsageRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Row> search(Filters filters, int offset, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id, account_id, model_id, protocol, req_type, ref_type, ref_id,
                       status, input_tokens, output_tokens, images, cost, currency, duration_ms, created_at
                FROM usage_records WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        appendWhere(sql, args, filters);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), (rs, i) -> new Row(
                rs.getLong("id"),
                nullableUuid(rs.getObject("user_id")),
                nullableUuid(rs.getObject("account_id")),
                nullableUuid(rs.getObject("model_id")),
                rs.getString("protocol"),
                rs.getString("req_type"),
                rs.getString("ref_type"),
                rs.getString("ref_id"),
                rs.getString("status"),
                nullableLong(rs.getObject("input_tokens")),
                nullableLong(rs.getObject("output_tokens")),
                rs.getObject("images") == null ? null : rs.getInt("images"),
                rs.getBigDecimal("cost"),
                rs.getString("currency"),
                nullableLong(rs.getObject("duration_ms")),
                rs.getTimestamp("created_at").toInstant()), args.toArray());
    }

    public long count(Filters filters) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM usage_records WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendWhere(sql, args, filters);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** 汇总卡片：总费用/总 token/请求数/成功数/成功率（A9）。 */
    public Map<String, Object> summary(Filters filters) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) AS request_count,
                       COUNT(*) FILTER (WHERE status = 'success' OR status = 'retried') AS success_count,
                       COALESCE(SUM(cost), 0) AS total_cost,
                       COALESCE(SUM(COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0)), 0) AS total_tokens
                FROM usage_records WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        appendWhere(sql, args, filters);
        Map<String, Object> raw = jdbcTemplate.queryForMap(sql.toString(), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        long requestCount = ((Number) raw.get("request_count")).longValue();
        long successCount = ((Number) raw.get("success_count")).longValue();
        out.put("requestCount", requestCount);
        out.put("successCount", successCount);
        out.put("successRate", requestCount == 0 ? null : Math.round(successCount * 10000.0 / requestCount) / 100.0);
        out.put("totalCost", raw.get("total_cost"));
        out.put("totalTokens", ((Number) raw.get("total_tokens")).longValue());
        return out;
    }

    /** CSV 导出源（当前筛选，≤10 万行，P2 异步化）。 */
    public List<Row> export(Filters filters) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id, account_id, model_id, protocol, req_type, ref_type, ref_id,
                       status, input_tokens, output_tokens, images, cost, currency, duration_ms, created_at
                FROM usage_records WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        appendWhere(sql, args, filters);
        sql.append(" ORDER BY created_at ASC, id ASC LIMIT ").append(EXPORT_LIMIT);
        return jdbcTemplate.query(sql.toString(), (rs, i) -> new Row(
                rs.getLong("id"),
                nullableUuid(rs.getObject("user_id")),
                nullableUuid(rs.getObject("account_id")),
                nullableUuid(rs.getObject("model_id")),
                rs.getString("protocol"),
                rs.getString("req_type"),
                rs.getString("ref_type"),
                rs.getString("ref_id"),
                rs.getString("status"),
                nullableLong(rs.getObject("input_tokens")),
                nullableLong(rs.getObject("output_tokens")),
                rs.getObject("images") == null ? null : rs.getInt("images"),
                rs.getBigDecimal("cost"),
                rs.getString("currency"),
                nullableLong(rs.getObject("duration_ms")),
                rs.getTimestamp("created_at").toInstant()), args.toArray());
    }

    /** T24 (WIN-29): 用户本人明细（A11 隔离——固定 userId 条件）。 */
    public List<Row> searchByUser(UUID userId, Instant from, Instant to, int offset, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id, account_id, model_id, protocol, req_type, ref_type, ref_id,
                       status, input_tokens, output_tokens, images, cost, currency, duration_ms, created_at
                FROM usage_records WHERE user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        appendTimeRange(sql, args, from, to);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return queryRows(sql.toString(), args.toArray());
    }

    /** T24 (WIN-29): 用户本人记录数（分页 total）。 */
    public long countByUser(UUID userId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM usage_records WHERE user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        appendTimeRange(sql, args, from, to);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** T24 (WIN-29): 用户本人汇总卡片（总费用/总 token/请求数/成功率）。 */
    public Map<String, Object> summaryByUser(UUID userId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) AS request_count,
                       COUNT(*) FILTER (WHERE status = 'success' OR status = 'retried') AS success_count,
                       COALESCE(SUM(cost), 0) AS total_cost,
                       COALESCE(SUM(COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0)), 0) AS total_tokens
                FROM usage_records WHERE user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        appendTimeRange(sql, args, from, to);
        Map<String, Object> raw = jdbcTemplate.queryForMap(sql.toString(), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        long requestCount = ((Number) raw.get("request_count")).longValue();
        long successCount = ((Number) raw.get("success_count")).longValue();
        out.put("requestCount", requestCount);
        out.put("successCount", successCount);
        out.put("successRate", requestCount == 0 ? null : Math.round(successCount * 10000.0 / requestCount) / 100.0);
        out.put("totalCost", raw.get("total_cost"));
        out.put("totalTokens", ((Number) raw.get("total_tokens")).longValue());
        return out;
    }

    /** T26 (WIN-29): 账号在某时间窗内的费用快照求和（月上限判定用）。 */
    public BigDecimal sumCostByAccount(UUID accountId, Instant from, Instant to) {
        String sql = "SELECT COALESCE(SUM(cost), 0) FROM usage_records WHERE account_id = ? AND created_at >= ? AND created_at < ?";
        BigDecimal sum = jdbcTemplate.queryForObject(sql, BigDecimal.class,
                accountId, Timestamp.from(from), Timestamp.from(to));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** T11: 保留期清理（NOVA_AUDIT_RETENTION_DAYS，默认 180 天）。 */
    public int deleteBefore(Instant cutoff) {
        return jdbcTemplate.update("DELETE FROM usage_records WHERE created_at < ?", Timestamp.from(cutoff));
    }

    /** Idempotency proof for tests (A20): the row exists exactly once. */
    public long countByRef(String refType, String refId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usage_records WHERE ref_type = ? AND ref_id = ?",
                Long.class, refType, refId);
        return count == null ? 0 : count;
    }

    private static void appendTimeRange(StringBuilder sql, List<Object> args, Instant from, Instant to) {
        if (from != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");
            args.add(Timestamp.from(to));
        }
    }

    private List<Row> queryRows(String sql, Object[] args) {
        return jdbcTemplate.query(sql, (rs, i) -> new Row(
                rs.getLong("id"),
                nullableUuid(rs.getObject("user_id")),
                nullableUuid(rs.getObject("account_id")),
                nullableUuid(rs.getObject("model_id")),
                rs.getString("protocol"),
                rs.getString("req_type"),
                rs.getString("ref_type"),
                rs.getString("ref_id"),
                rs.getString("status"),
                nullableLong(rs.getObject("input_tokens")),
                nullableLong(rs.getObject("output_tokens")),
                rs.getObject("images") == null ? null : rs.getInt("images"),
                rs.getBigDecimal("cost"),
                rs.getString("currency"),
                nullableLong(rs.getObject("duration_ms")),
                rs.getTimestamp("created_at").toInstant()), args);
    }

    private static void appendWhere(StringBuilder sql, List<Object> args, Filters f) {
        if (f == null) {
            return;
        }
        if (f.from() != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            sql.append(" AND created_at <= ?");
            args.add(Timestamp.from(f.to()));
        }
        if (f.userId() != null) {
            sql.append(" AND user_id = ?");
            args.add(f.userId());
        }
        if (f.modelId() != null) {
            sql.append(" AND model_id = ?");
            args.add(f.modelId());
        }
        if (f.accountId() != null) {
            sql.append(" AND account_id = ?");
            args.add(f.accountId());
        }
        if (f.protocol() != null && !f.protocol().isBlank()) {
            sql.append(" AND protocol = ?");
            args.add(f.protocol());
        }
        if (f.reqType() != null && !f.reqType().isBlank()) {
            sql.append(" AND req_type = ?");
            args.add(f.reqType());
        }
        if (f.status() != null && !f.status().isBlank()) {
            sql.append(" AND status = ?");
            args.add(f.status());
        }
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

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
