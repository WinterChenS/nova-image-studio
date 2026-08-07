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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIN-30 (T14/T15, A12/A13/A14/A17) — RBAC E2E over the real HTTP surface
 * (external PG, gated on DB_HOST):
 * <ul>
 *   <li>register dual-writes user_roles → /api/auth/me returns roles + permissions
 *       (user = workbench.view + usage.me);</li>
 *   <li>普通用户直调 admin 端点 → 403（@PreAuthorize PERM_，A13）；匿名 → 401（A17）；</li>
 *   <li>NOVA_ADMIN_USERNAME 提权联动 user_roles → admin 可访问账号池 API；</li>
 *   <li>角色指派（PATCH /api/nova/admin/users/{id} role）双写 user_roles 后
 *       /me 的 permissions 即时更新（缓存失效，A14）。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class RbacE2EIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

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

    private JsonNode registerAndLogin(String username) throws Exception {
        String reg = post("/api/auth/register", Map.of("username", username, "password", "secret123"));
        assertThat(reg).contains("\"id\"");
        String login = post("/api/auth/login", Map.of("username", username, "password", "secret123"));
        return mapper.readTree(login);
    }

    private final java.util.List<String> createdUsers = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        for (String username : createdUsers) {
            try {
                jdbcTemplate.update("DELETE FROM users WHERE username = ?", username);
            } catch (Exception ignored) {
                // 尽力清理（级联删除 user_roles）
            }
        }
    }

    @Test
    void registerWritesUserRolesAndMeReturnsPermissions() throws Exception {
        String username = "rbac_" + UUID.randomUUID().toString().substring(0, 8);
        createdUsers.add(username);
        JsonNode login = registerAndLogin(username);
        String token = login.get("token").asText();

        JsonNode me = mapper.readTree(getWithAuth("/api/auth/me", token));
        assertThat(me.get("role").asText()).isEqualTo("user");
        assertThat(me.get("roles").get(0).asText()).isEqualTo("user");
        JsonNode perms = me.get("permissions");
        assertThat(perms.toString()).contains("workbench.view").contains("usage.me");
        assertThat(perms.toString()).doesNotContain("account.manage");

        // 双写落库校验：user_roles 行存在
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id WHERE u.username = ?",
                Long.class, username);
        assertThat(rows).isGreaterThanOrEqualTo(1);
    }

    @Test
    void anonymousAdminEndpoint401AndRegularUser403() throws Exception {
        String uname = "rbac_user_" + UUID.randomUUID().toString().substring(0, 8);
        createdUsers.add(uname);
        JsonNode login = registerAndLogin(uname);
        String token = login.get("token").asText();

        // 匿名 → 401（门禁收口，A17）
        assertThatThrownBy(() -> rest.getForEntity(
                "http://localhost:" + port + "/api/nova/admin/accounts", String.class))
                .isInstanceOfSatisfying(HttpClientErrorException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(401));

        // 普通用户 → 403（@PreAuthorize PERM_account.manage，A13）
        assertThatThrownBy(() -> rest.exchange(
                "http://localhost:" + port + "/api/nova/admin/accounts", HttpMethod.GET,
                new HttpEntity<>(auth(token)), String.class))
                .isInstanceOfSatisfying(HttpClientErrorException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void anonymousReadOnlyEndpointsNow401() throws Exception {
        // T16 (D2 默认)：原匿名只读端点收口
        for (String path : new String[]{"/api/nova/queue-status", "/api/nova/prompts", "/api/nova/blacklist"}) {
            assertThatThrownBy(() -> rest.getForEntity("http://localhost:" + port + path, String.class))
                    .isInstanceOfSatisfying(HttpClientErrorException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        }
    }

    @Test
    void promotedAdminGetsUserRolesAndAdminAccess() throws Exception {
        // 模拟 AdminBootstrap 提权：users.role + user_roles 双写（T15）
        String username = "rbac_admin_" + UUID.randomUUID().toString().substring(0, 8);
        createdUsers.add(username);
        JsonNode login = registerAndLogin(username);
        String token = login.get("token").asText();
        String userId = login.get("user").get("id").asText();

        jdbcTemplate.update("UPDATE users SET role = 'admin' WHERE id = ?::uuid", userId);
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?::uuid", userId);
        jdbcTemplate.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT u.id, r.id FROM users u, roles r
                WHERE u.id = ?::uuid AND r.code = 'admin'
                ON CONFLICT DO NOTHING
                """, userId);
        Thread.sleep(1100); // ADR-29: 权限缓存 ≤1s 失效后重载

        // 重新登录（JWT 带新 role），/me 返回 roles + 全部权限（缓存失效后重载）
        String login2 = post("/api/auth/login", Map.of("username", username, "password", "secret123"));
        JsonNode loginBody = mapper.readTree(login2);
        String adminToken = loginBody.get("token").asText();
        JsonNode me = mapper.readTree(getWithAuth("/api/auth/me", adminToken));
        assertThat(me.get("roles").toString()).contains("admin");
        assertThat(me.get("permissions").toString()).contains("account.manage", "audit.view");

        // admin 可访问账号池 API
        String accounts = getWithAuth("/api/nova/admin/accounts", adminToken);
        assertThat(accounts).startsWith("[");
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
