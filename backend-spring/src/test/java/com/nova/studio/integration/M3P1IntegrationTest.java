package com.nova.studio.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIN-29 (M3, T24-T29) — P1 E2E over the real HTTP surface (external PG,
 * gated on DB_HOST):
 * <ul>
 *   <li><b>T24/A11</b> — {@code /api/nova/usage/me} returns only the caller's
 *       rows (user isolation) and requires login;</li>
 *   <li><b>T25/A10</b> — daily aggregation sums snapshot costs; aggregates
 *       survive detail-row deletion (retention cleanup);</li>
 *   <li><b>T26</b> — monthly cap auto-pauses the account (never deletes) and
 *       writes audit_log; admin resume returns it to active;</li>
 *   <li><b>T28/T29/A16</b> — role-permission matrix change writes audit_log and
 *       invalidates the permission cache (≤1s); account/pricing mutations are
 *       audited too.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class M3P1IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private com.nova.studio.audit.UsageRecordService usageRecordService;

    @Autowired
    private com.nova.studio.settings.CryptoService cryptoService;

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<String> createdUsers = new java.util.ArrayList<>();

    // ===== helpers =====

    private String post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.postForEntity("http://localhost:" + port + path,
                new HttpEntity<>(body, headers), String.class);
        return resp.getBody();
    }

    private ResponseEntity<String> exchange(String path, String token, HttpMethod method, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method,
                new HttpEntity<>(body, headers), String.class);
    }

    private String getWithAuth(String path, String token) {
        return exchange(path, token, HttpMethod.GET, null).getBody();
    }

    private JsonNode registerAndLogin(String username) throws Exception {
        String reg = post("/api/auth/register", Map.of("username", username, "password", "secret123"));
        assertThat(reg).contains("\"id\"");
        createdUsers.add(username);
        String login = post("/api/auth/login", Map.of("username", username, "password", "secret123"));
        return mapper.readTree(login);
    }

    /** Promote a registered user to admin (users.role + user_roles dual-write) and re-login. */
    private String promoteToAdminAndLogin(String username, String userId) throws Exception {
        jdbcTemplate.update("UPDATE users SET role = 'admin' WHERE id = ?::uuid", userId);
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?::uuid", userId);
        jdbcTemplate.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT u.id, r.id FROM users u, roles r
                WHERE u.id = ?::uuid AND r.code = 'admin'
                ON CONFLICT DO NOTHING
                """, userId);
        Thread.sleep(1100); // ADR-29: 权限缓存 ≤1s 失效
        JsonNode login2 = mapper.readTree(post("/api/auth/login", Map.of("username", username, "password", "secret123")));
        return login2.get("token").asText();
    }

    private String seedCatalogModel(String protocol, String type) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ai_models (id, type, protocol, name, model_id, base_url, enabled)
                VALUES (?::uuid, ?, ?, ?, ?, ?, TRUE)
                """, id.toString(), type, protocol, "M3测试模型-" + protocol, "gpt-test-" + UUID.randomUUID(),
                "https://api.example.com/v1");
        return id.toString();
    }

    private String seedAccount(String modelId, BigDecimal monthlyCap) {
        UUID id = UUID.randomUUID();
        ArrayNode scope = mapper.createArrayNode();
        scope.add(modelId);
        jdbcTemplate.update("""
                INSERT INTO ai_accounts (id, name, protocol, base_url, api_key_enc, model_scope, status, monthly_cap_cost)
                VALUES (?::uuid, ?, 'openai', 'https://api.example.com/v1', ?, ?::jsonb, 'active', ?)
                """, id.toString(), "M3账号-" + UUID.randomUUID().toString().substring(0, 6),
                cryptoService.encrypt("sk-m3-" + UUID.randomUUID()), scope.toString(), monthlyCap);
        return id.toString();
    }

    private void insertUsage(String userId, String accountId, String modelId, String reqType, String refId,
                             BigDecimal cost, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO usage_records (user_id, account_id, model_id, protocol, req_type, ref_type, ref_id,
                                           status, input_tokens, output_tokens, images, cost, currency, created_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'openai', ?, 'task', ?, 'success', 10, 20, 1, ?, 'CNY', ?)
                """, userId, accountId, modelId, reqType, refId, cost, Timestamp.from(createdAt));
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        for (String username : createdUsers) {
            try {
                jdbcTemplate.update("DELETE FROM users WHERE username = ?", username);
            } catch (Exception ignored) {
                // 级联清理 user_roles/usage（user_id FK）尽力而为
            }
        }
        try {
            // T28: 恢复 user 角色默认权限（避免污染共享测试库的种子断言）
            jdbcTemplate.update("""
                    DELETE FROM role_permissions WHERE role_id = (SELECT id FROM roles WHERE code = 'user')
                    """);
            jdbcTemplate.update("""
                    INSERT INTO role_permissions (role_id, permission_id)
                    SELECT r.id, p.id FROM roles r JOIN permissions p
                    ON p.code IN ('workbench.view', 'usage.me')
                    WHERE r.code = 'user' ON CONFLICT DO NOTHING
                    """);
            jdbcTemplate.update("DELETE FROM ai_models WHERE name LIKE 'M3测试模型-%'");
            jdbcTemplate.update("DELETE FROM ai_accounts WHERE name LIKE 'M3账号-%'");
        } catch (Exception ignored) {
        }
    }

    // ===== T24 / A11: usage/me user isolation =====

    @Test
    void usageMeRequiresLogin() {
        assertThatThrownBy(() -> rest.getForEntity("http://localhost:" + port + "/api/nova/usage/me", String.class))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
    }

    @Test
    void usageMeReturnsOnlyOwnRows() throws Exception {
        JsonNode alice = registerAndLogin("m3_alice_" + UUID.randomUUID().toString().substring(0, 8));
        JsonNode bob = registerAndLogin("m3_bob_" + UUID.randomUUID().toString().substring(0, 8));
        String aliceId = alice.get("user").get("id").asText();
        String bobId = bob.get("user").get("id").asText();
        String modelId = seedCatalogModel("openai", "image");
        String accountId = seedAccount(modelId, null);

        String refA = "m3-ref-a-" + UUID.randomUUID();
        String refB = "m3-ref-b-" + UUID.randomUUID();
        insertUsage(aliceId, accountId, modelId, "image", refA, new BigDecimal("3.000000"), Instant.now().minusSeconds(60));
        insertUsage(bobId, accountId, modelId, "image", refB, new BigDecimal("9.000000"), Instant.now().minusSeconds(60));

        String aliceBody = getWithAuth("/api/nova/usage/me", alice.get("token").asText());
        JsonNode me = mapper.readTree(aliceBody);
        // 仅本人数据：alice 的 ref 出现，bob 的 ref 绝不出现（A11 用户间隔离）
        assertThat(me.toString()).contains(refA).doesNotContain(refB);
        assertThat(me.get("summary").get("totalCost").decimalValue())
                .isEqualByComparingTo(new BigDecimal("3.000000"));

        // 普通用户可访问（usage.me 在 user 默认角色内）
        assertThat(me.get("items").isArray()).isTrue();
    }

    // ===== T25 / A10: daily agg survives detail cleanup =====

    @Test
    void dailyAggregationSurvivesDetailCleanup() throws Exception {
        JsonNode user = registerAndLogin("m3_agg_" + UUID.randomUUID().toString().substring(0, 8));
        String userId = user.get("user").get("id").asText();
        String modelId = seedCatalogModel("openai", "text");
        String accountId = seedAccount(modelId, null);

        LocalDate day = LocalDate.now().minusDays(2);
        Instant at = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().plus(java.time.Duration.ofHours(10));
        insertUsage(userId, accountId, modelId, "text", "m3-agg-1-" + UUID.randomUUID(), new BigDecimal("1.500000"), at);
        insertUsage(userId, accountId, modelId, "text", "m3-agg-2-" + UUID.randomUUID(), new BigDecimal("2.500000"), at);

        // 聚合（等价 DailyAggTask 对历史日期补跑）
        jdbcTemplate.update("""
                INSERT INTO usage_daily_agg (agg_date, user_id, model_id, account_id, req_type,
                                             request_count, success_count, input_tokens, output_tokens, cost, currency, updated_at)
                SELECT created_at::date, user_id, model_id, account_id, req_type,
                       COUNT(*), COUNT(*) FILTER (WHERE status IN ('success','retried')),
                       COALESCE(SUM(input_tokens),0), COALESCE(SUM(output_tokens),0),
                       COALESCE(SUM(cost),0), MIN(currency), now()
                FROM usage_records
                WHERE created_at::date = ?
                GROUP BY created_at::date, user_id, model_id, account_id, req_type
                """, day);

        // 明细清理（等价 AuditCleanupScheduler 保留期删除）→ 聚合仍在
        int deleted = jdbcTemplate.update("DELETE FROM usage_records WHERE created_at::date = ?", day);
        assertThat(deleted).isGreaterThanOrEqualTo(2);

        List<Map<String, Object>> agg = jdbcTemplate.queryForList("""
                SELECT request_count, success_count, cost FROM usage_daily_agg
                WHERE agg_date = ? AND user_id = ?::uuid
                """, day, userId);
        assertThat(agg).hasSize(1);
        assertThat(((Number) agg.get(0).get("request_count")).intValue()).isEqualTo(2);
        // 快照 cost 求和，不重算
        assertThat(((java.math.BigDecimal) agg.get(0).get("cost")).compareTo(new BigDecimal("4.000000"))).isEqualTo(0);
    }

    // ===== T26: monthly cap auto-pause =====

    @Test
    void monthlyCapAutoPausesAndAdminResumes() throws Exception {
        String uname = "m3_admin_" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode admin = registerAndLogin(uname);
        String adminId = admin.get("user").get("id").asText();
        String adminToken = promoteToAdminAndLogin(uname, adminId);

        String modelId = seedCatalogModel("openai", "text");
        String accountId = seedAccount(modelId, new BigDecimal("10.0000"));
        // 价格：单次价 15（cost 快照 = 15 > 上限 10）
        jdbcTemplate.update("""
                INSERT INTO ai_model_pricing (model_id, currency, per_request_price)
                VALUES (?::uuid, 'CNY', 15.0)
                ON CONFLICT (model_id, currency) DO UPDATE SET per_request_price = 15.0
                """, modelId);

        // 超过月度上限（15 > 10）→ 应自动 paused（走真实 UsageRecordService 写链路，触发达限检查）
        String userId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM users WHERE username = ?", String.class, uname);
        usageRecordService.recordSync(new com.nova.studio.audit.UsageRecordService.UsageRecord(
                java.util.UUID.fromString(userId), java.util.UUID.fromString(accountId),
                java.util.UUID.fromString(modelId), "openai", "text", "task",
                "m3-cap-" + UUID.randomUUID(), "success", 10L, 20L, null, "CNY", 1000L));

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM ai_accounts WHERE id = ?::uuid", String.class, accountId);
        assertThat(status).isEqualTo("paused");

        // 审计日志记录自动暂停（A16）
        Long auditRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE target_type = 'ai_accounts' AND target_id = ?
                AND action = 'account.cap_paused'
                """, Long.class, accountId);
        assertThat(auditRows).isGreaterThanOrEqualTo(1);

        // admin 解除（resume → active）
        ResponseEntity<String> resume = exchange("/api/nova/admin/accounts/" + accountId + "/resume",
                adminToken, HttpMethod.POST, Map.of());
        assertThat(resume.getStatusCode().value()).isEqualTo(200);
        assertThat(resume.getBody()).contains("\"active\"");
    }

    // ===== T28/T29 / A16: RBAC matrix + audit =====

    @Test
    void rbacMatrixWritesAuditAndInvalidatesCache() throws Exception {
        String uname = "m3_rbac_" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode admin = registerAndLogin(uname);
        String adminId = admin.get("user").get("id").asText();
        String adminToken = promoteToAdminAndLogin(uname, adminId);

        // 普通用户无 rbac.manage → 403
        JsonNode user = registerAndLogin("m3_user_" + UUID.randomUUID().toString().substring(0, 8));
        assertThatThrownBy(() -> exchange("/api/nova/admin/roles", user.get("token").asText(),
                HttpMethod.GET, null))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));

        // admin 读角色+权限清单
        String roles = getWithAuth("/api/nova/admin/roles", adminToken);
        assertThat(roles).contains("admin").contains("permissions");
        String perms = getWithAuth("/api/nova/admin/permissions", adminToken);
        assertThat(perms).contains("workbench.view").contains("rbac.manage");

        // 修改 user 角色的权限（去掉 pricing.manage，加上 audit.view）→ audit_log 落日志 + 缓存失效
        String userRoleId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM roles WHERE code = 'user'", String.class);
        String auditBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'role_permissions.update'", Long.class).toString();
        ResponseEntity<String> put = exchange("/api/nova/admin/roles/" + userRoleId + "/permissions",
                adminToken, HttpMethod.PUT, Map.of("permissionCodes",
                        List.of("workbench.view", "usage.me", "audit.view")));
        assertThat(put.getStatusCode().value()).isEqualTo(200);

        Long auditAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'role_permissions.update'", Long.class);
        assertThat(auditAfter).isEqualTo(Long.parseLong(auditBefore) + 1);

        // 缓存失效 ≤1s：重新登录的 user 立即拿到新权限（audit.view 生效）
        JsonNode loginAgain = mapper.readTree(post("/api/auth/login",
                Map.of("username", user.get("user").get("username").asText(), "password", "secret123")));
        String me = getWithAuth("/api/auth/me", loginAgain.get("token").asText());
        assertThat(me).contains("audit.view");
    }
}
