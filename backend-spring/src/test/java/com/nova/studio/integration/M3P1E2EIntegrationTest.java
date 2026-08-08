package com.nova.studio.integration;

import com.nova.studio.accountpool.AccountMonthlyCapService;
import com.nova.studio.accountpool.AccountRepository;
import com.nova.studio.accountpool.AccountService;
import com.nova.studio.audit.AuditLogService;
import com.nova.studio.audit.UsageAggRepository;
import com.nova.studio.audit.UsageRecordMapper;
import com.nova.studio.audit.UsageRecordRepository;
import com.nova.studio.rbac.RbacRepository;
import com.nova.studio.rbac.RbacService;
import com.nova.studio.rbac.UserPermissionService;
import com.nova.studio.settings.CryptoService;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIN-29 (M3, T24/T25/T26/T28/T29) — P1 E2E over the real HTTP + PG surface
 * (gated on DB_HOST, same as the M2 E2E classes):
 * <ul>
 *   <li>A11: GET /api/nova/usage/me 仅返回本人记录（用户间隔离）；</li>
 *   <li>A10: usage_daily_agg 按明细快照 cost 聚合，明细删除后聚合仍在；</li>
 *   <li>A16: 账号/角色×权限变更写入 audit_log；</li>
 *   <li>T26: monthly_cap_cost 达限自动 paused（不删除），未达限不动。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class M3P1E2EIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UsageRecordMapper usageRecordMapper;
    @Autowired
    private UsageRecordRepository usageRecordRepository;
    @Autowired
    private UsageAggRepository usageAggRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountService accountService;
    @Autowired
    private AccountMonthlyCapService monthlyCapService;
    @Autowired
    private CryptoService cryptoService;
    @Autowired
    private AuditLogService auditLogService;
    @Autowired
    private RbacRepository rbacRepository;
    @Autowired
    private RbacService rbacService;
    @Autowired
    private UserPermissionService permissionService;

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.List<String> createdUsers = new java.util.ArrayList<>();

    /** Registered session (username + JWT) so tests can resolve the userId. */
    private record AuthSession(String username, String token) {
    }

    private String post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> resp = rest.postForEntity("http://localhost:" + port + path, entity, String.class);
        return resp.getBody();
    }

    private String getWithAuth(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = rest.exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        return resp.getBody();
    }

    private AuthSession registerAndLogin(String username) throws Exception {
        createdUsers.add(username);
        post("/api/auth/register", Map.of("username", username, "password", "secret123"));
        String login = post("/api/auth/login", Map.of("username", username, "password", "secret123"));
        String token = mapper.readTree(login).get("token").asText();
        return new AuthSession(username, token);
    }

    private UUID userIdOf(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", UUID.class, username);
    }

    private void insertUsage(UUID userId, UUID modelId, UUID accountId, String refId, BigDecimal cost, Instant createdAt) {
        usageRecordMapper.insertIgnore(userId, accountId, modelId, "openai", "text", "proxy", refId,
                "success", 10L, 5L, null, cost, "CNY", 100L, createdAt);
    }

    @AfterEach
    void cleanup() {
        // WIN-40 复测修复：先清依赖 users 的聚合/审计数据（原顺序在删用户之后执行，子查询失效导致残留）
        jdbcTemplate.update("DELETE FROM usage_daily_agg WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'm3_%')");
        jdbcTemplate.update("DELETE FROM usage_records WHERE ref_id LIKE 'm3-%'");
        jdbcTemplate.update("DELETE FROM audit_log WHERE target_id LIKE 'm3-%'");
        jdbcTemplate.update("DELETE FROM ai_model_pricing WHERE model_id IN (SELECT id FROM ai_models WHERE name LIKE 'M3%')");
        jdbcTemplate.update("DELETE FROM ai_accounts WHERE name LIKE 'M3-%'");
        jdbcTemplate.update("DELETE FROM ai_models WHERE name LIKE 'M3%'");
        for (String username : createdUsers) {
            try {
                jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE username = ?)", username);
                jdbcTemplate.update("DELETE FROM users WHERE username = ?", username);
            } catch (Exception ignored) {
            }
        }
        accountService.invalidateCaches();
        permissionService.invalidateAll();
    }

    // ===== T24 (A11): 我的用量仅本人 =====

    @Test
    void myUsageIsScopedToCallerOnly() throws Exception {
        AuthSession a = registerAndLogin("m3_a_" + UUID.randomUUID().toString().substring(0, 6));
        AuthSession b = registerAndLogin("m3_b_" + UUID.randomUUID().toString().substring(0, 6));
        UUID aId = userIdOf(a.username());
        UUID modelId = catalogSeed();
        insertUsage(aId, modelId, null, "m3-usage-a-1", new BigDecimal("0.50"), Instant.now());
        UUID bId = userIdOf(b.username());
        insertUsage(bId, modelId, null, "m3-usage-b-1", new BigDecimal("9.99"), Instant.now());

        String body = getWithAuth("/api/nova/usage/me?page=1&size=50", a.token());
        JsonNode resp = mapper.readTree(body);
        assertThat(resp.get("total").asLong()).isEqualTo(1);
        assertThat(resp.get("items").get(0).get("refId").asText()).isEqualTo("m3-usage-a-1");
        assertThat(resp.get("summary").get("totalCost").decimalValue()).isEqualByComparingTo("0.50");
    }

    // ===== T25 (A10): 日聚合 = 明细快照求和，明细清理后聚合仍在 =====

    @Test
    void dailyAggregationSurvivesDetailCleanup() throws Exception {
        AuthSession agg = registerAndLogin("m3_agg_" + UUID.randomUUID().toString().substring(0, 6));
        UUID u = userIdOf(agg.username());
        UUID modelId = catalogSeed();
        UUID accountId = accountRepository.insert("M3-聚合账号", "openai", "https://api.example.com/v1",
                cryptoService.encrypt("sk-m3"), "[]", 100, null, null, null);
        insertUsage(u, modelId, accountId, "m3-agg-1", new BigDecimal("1.00"), Instant.parse("2026-08-06T12:00:00Z"));
        insertUsage(u, modelId, accountId, "m3-agg-2", new BigDecimal("2.00"), Instant.parse("2026-08-06T13:00:00Z"));

        int inserted = usageAggRepository.aggregateDay(LocalDate.of(2026, 8, 6), "Asia/Shanghai");
        assertThat(inserted).isGreaterThanOrEqualTo(1);

        Long aggCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM usage_daily_agg
                WHERE user_id = ? AND agg_date = DATE '2026-08-06' AND cost = 3.00
                """, Long.class, u);
        assertThat(aggCount).isEqualTo(1);

        // 模拟保留期清理：删除明细 → 聚合仍在（A10）
        usageRecordRepository.deleteBefore(Instant.parse("2026-08-07T00:00:00Z"));
        Long aggAfter = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM usage_daily_agg WHERE user_id = ? AND agg_date = DATE '2026-08-06'
                """, Long.class, u);
        assertThat(aggAfter).isEqualTo(1);
    }

    // ===== T26: monthly_cap_cost 达限自动 paused =====

    @Test
    void monthlyCapAutoPausesAccountWhenReached() throws Exception {
        AuthSession cap = registerAndLogin("m3_cap_" + UUID.randomUUID().toString().substring(0, 6));
        UUID u = userIdOf(cap.username());
        UUID modelId = catalogSeed();
        UUID accountId = accountRepository.insert("M3-上限账号", "openai", "https://api.example.com/v1",
                cryptoService.encrypt("sk-m3"), "[]", 100, new BigDecimal("5.00"), null, null);
        insertUsage(u, modelId, accountId, "m3-cap-1", new BigDecimal("6.00"), Instant.now());
        accountService.invalidateCaches();

        monthlyCapService.checkAllAccounts();

        String status = jdbcTemplate.queryForObject("SELECT status FROM ai_accounts WHERE id = ?", String.class, accountId);
        assertThat(status).isEqualTo("paused");
    }

    // ===== T29 (A16): 账号/角色×权限变更落 audit_log =====

    @Test
    void accountAndMatrixChangesWriteAuditLog() throws Exception {
        AuthSession audit = registerAndLogin("m3_audit_" + UUID.randomUUID().toString().substring(0, 6));
        UUID actor = userIdOf(audit.username());

        UUID accountId = accountRepository.insert("M3-审计账号", "openai", "https://api.example.com/v1",
                cryptoService.encrypt("sk-m3"), "[]", 100, null, null, null);
        accountService.pause(actor, accountId);

        Long accountAudits = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE target_type = 'ai_accounts' AND target_id = ? AND action = 'account.pause'
                """, Long.class, accountId.toString());
        assertThat(accountAudits).isEqualTo(1);

        // 角色×权限矩阵变更 → role_permissions.update（before/after 权限码）
        UUID userRoleId = rbacRepository.listRoles().stream()
                .filter(r -> "user".equals(r.code())).findFirst().orElseThrow().id();
        UUID usagePermId = rbacRepository.listPermissions().stream()
                .filter(p -> "usage.me".equals(p.code())).findFirst().orElseThrow().id();
        // 自愈（WIN-40 复测修复）：直接 JDBC 恢复 user 角色 seed 权限 + 清理 audit 残留
        // （不经 RbacService 以免写入 audit 行污染计数）
        jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ?::uuid", userRoleId.toString());
        jdbcTemplate.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?::uuid, id FROM permissions WHERE code IN ('workbench.view', 'usage.me')
                """, userRoleId.toString());
        jdbcTemplate.update("DELETE FROM audit_log WHERE target_type = 'role_permissions' AND target_id = ?",
                userRoleId.toString());
        permissionService.invalidateAll();

        rbacService.updateRolePermissions(actor, userRoleId, java.util.List.of(usagePermId));

        Long matrixAudits = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE target_type = 'role_permissions' AND target_id = ? AND action = 'role_permissions.update'
                """, Long.class, userRoleId.toString());
        assertThat(matrixAudits).isEqualTo(1);

        // 恢复 user 角色 seed 权限（workbench.view + usage.me），避免污染其它用例/共享库
        jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ?::uuid", userRoleId.toString());
        jdbcTemplate.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?::uuid, id FROM permissions WHERE code IN ('workbench.view', 'usage.me')
                """, userRoleId.toString());
        permissionService.invalidateAll();
    }

    private UUID catalogSeed() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO ai_models (type, protocol, name, model_id, base_url, capabilities, enabled)
                VALUES ('text', 'openai-chat-completions', 'M3文本模型', 'm3-gpt', 'https://api.example.com/v1', '{}', TRUE)
                RETURNING id
                """, UUID.class);
    }
}
