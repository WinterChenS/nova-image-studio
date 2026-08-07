package com.nova.studio.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIN-40 T3 E2E — Agent 会话持久化 API（真实 PG + JWT）：会话 CRUD、消息分页
 * （before 游标）、pending/context_summary 读写、title 自动摘要、软删回收站恢复、
 * 属主隔离（AC-10）、图片目录上传。AC-1 基础（F1 写点可经 API 完成，pending 可恢复）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class AgentConversationE2EIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private final RestTemplate rest = createRestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private String token;
    private String otherToken;
    private String username;
    private String otherUsername;

    private static RestTemplate createRestTemplate() {
        RestTemplate rt = new RestTemplate(new JdkClientHttpRequestFactory());
        rt.setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return rt;
    }

    @BeforeEach
    void setUp() {
        username = "conv_e2e_" + UUID.randomUUID().toString().substring(0, 8);
        otherUsername = "conv_e2e_" + UUID.randomUUID().toString().substring(0, 8);
        token = registerAndLogin(username);
        otherToken = registerAndLogin(otherUsername);
    }

    @AfterEach
    void tearDown() {
        // 清理测试用户及其新表数据（conversations/messages 按 user 级联清理）
        String userId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM users WHERE username = ?", String.class, username);
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM conversation_messages WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM conversations WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM assets WHERE user_id = ? AND source_kind = 'conversation'", userId);
        }
        String otherId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM users WHERE username = ?", String.class, otherUsername);
        if (otherId != null) {
            jdbcTemplate.update("DELETE FROM conversation_messages WHERE user_id = ?", otherId);
            jdbcTemplate.update("DELETE FROM conversations WHERE user_id = ?", otherId);
            jdbcTemplate.update("DELETE FROM assets WHERE user_id = ? AND source_kind = 'conversation'", otherId);
        }
        jdbcTemplate.update("DELETE FROM users WHERE username IN (?, ?)", username, otherUsername);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private String registerAndLogin(String name) {
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("username", name);
        reg.put("password", "secret123");
        postJson("/api/auth/register", reg, null);
        Map<String, Object> login = new LinkedHashMap<>();
        login.put("username", name);
        login.put("password", "secret123");
        JsonNode resp = postJson("/api/auth/login", login, null);
        return resp.get("token").asText();
    }

    private JsonNode postJson(String path, Object body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        ResponseEntity<String> resp = rest.postForEntity(base() + path,
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("POST %s 应成功: %s", path, resp.getBody()).isTrue();
        return parse(resp);
    }

    private JsonNode send(String method, String path, Object body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        ResponseEntity<String> resp = rest.exchange(base() + path,
                HttpMethod.valueOf(method), new HttpEntity<>(body, headers), String.class);
        return parse(resp);
    }

    private JsonNode get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        ResponseEntity<String> resp = rest.exchange(base() + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        return parse(resp);
    }

    private JsonNode parse(ResponseEntity<String> resp) {
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应解析失败: " + resp.getBody(), e);
        }
    }

    // ===== 用例 =====

    @Test
    void fullConversationLifecycle() {
        // 新建会话
        JsonNode created = postJson("/api/nova/agent/conversations",
                Map.of("title", "测试会话"), token);
        String convId = created.get("id").asText();
        assertThat(created.get("status").asText()).isEqualTo("active");
        assertThat(created.get("title").asText()).isEqualTo("测试会话");

        // 首条 user 消息 → title 自动摘要（未命名会话时）
        JsonNode msg = postJson("/api/nova/agent/conversations/" + convId + "/messages",
                Map.of("role", "user", "text", "帮我生成一张赛博朋克猫咪图片，要有霓虹灯光效"), token);
        assertThat(msg.get("role").asText()).isEqualTo("user");
        JsonNode detail = get("/api/nova/agent/conversations/" + convId, token);
        assertThat(detail.get("title").asText()).isNotEqualTo("未命名会话");

        // assistant 消息（withdrawable）
        postJson("/api/nova/agent/conversations/" + convId + "/messages",
                Map.of("role", "assistant", "text", "好的，正在生成", "withdrawable", true), token);

        // 消息分页：默认最近 50 条
        JsonNode page = get("/api/nova/agent/conversations/" + convId + "/messages", token);
        assertThat(page.get("items").size()).isEqualTo(2);

        // pending 读写（FR-1.2 中断恢复载体）
        send("PATCH", "/api/nova/agent/conversations/" + convId,
                Map.of("pending", Map.of("kind", "proposal", "taskId", "t-1")), token);
        JsonNode withPending = get("/api/nova/agent/conversations/" + convId, token);
        assertThat(withPending.get("pending").get("kind").asText()).isEqualTo("proposal");

        // 清空 pending（恢复完成后）
        Map<String, Object> clearPending = new java.util.HashMap<>();
        clearPending.put("pending", null);
        send("PATCH", "/api/nova/agent/conversations/" + convId, clearPending, token);
        JsonNode cleared = get("/api/nova/agent/conversations/" + convId, token);
        assertThat(cleared.get("pending").isNull()).isTrue();

        // context_summary 读写（ADR-44 压缩摘要就绪）
        send("PATCH", "/api/nova/agent/conversations/" + convId,
                Map.of("contextSummary", Map.of("text", "摘要", "foldedCount", 3)), token);
        JsonNode withSummary = get("/api/nova/agent/conversations/" + convId, token);
        assertThat(withSummary.get("contextSummary").get("foldedCount").asInt()).isEqualTo(3);

        // 会话列表 last_message_at 倒序
        JsonNode list = get("/api/nova/agent/conversations?status=active", token);
        assertThat(list.get("items").size()).isGreaterThanOrEqualTo(1);

        // 软删 → 回收站可见 → 恢复
        send("DELETE", "/api/nova/agent/conversations/" + convId, null, token);
        JsonNode recycle = get("/api/nova/agent/conversations?status=recycle", token);
        assertThat(recycle.get("items").size()).isGreaterThanOrEqualTo(1);
        postJson("/api/nova/agent/conversations/" + convId + "/restore", Map.of(), token);
        JsonNode restored = get("/api/nova/agent/conversations/" + convId, token);
        assertThat(restored.get("status").asText()).isEqualTo("active");
    }

    @Test
    void messagePaginationBeforeCursor() {
        JsonNode created = postJson("/api/nova/agent/conversations", Map.of(), token);
        String convId = created.get("id").asText();
        for (int i = 1; i <= 5; i++) {
            postJson("/api/nova/agent/conversations/" + convId + "/messages",
                    Map.of("role", "user", "text", "消息" + i), token);
        }
        // 首屏最近 2 条（limit=2）
        JsonNode first = get("/api/nova/agent/conversations/" + convId + "/messages?limit=2", token);
        assertThat(first.get("items").size()).isEqualTo(2);
        String cursor = first.get("nextBefore").asText();
        // 用游标加载更早的
        JsonNode earlier = get("/api/nova/agent/conversations/" + convId + "/messages?limit=2&before=" + cursor, token);
        assertThat(earlier.get("items").size()).isEqualTo(2);
    }

    @Test
    void ownershipIsolationAcrossUsers() {
        JsonNode created = postJson("/api/nova/agent/conversations", Map.of("title", "A 的会话"), token);
        String convId = created.get("id").asText();
        postJson("/api/nova/agent/conversations/" + convId + "/messages",
                Map.of("role", "user", "text", "A 的消息"), token);

        // 用户 B 无法读取 A 的会话（AC-10）
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(otherToken);
        ResponseEntity<String> resp = rest.exchange(base() + "/api/nova/agent/conversations/" + convId,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        // B 也无法向 A 的会话追加消息
        ResponseEntity<String> append = rest.exchange(
                base() + "/api/nova/agent/conversations/" + convId + "/messages",
                HttpMethod.POST, new HttpEntity<>(Map.of("role", "user", "text", "越权"),
                        headersWithJson(otherToken)), String.class);
        assertThat(append.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void conversationImageDirectoryViaAssets() {
        JsonNode created = postJson("/api/nova/agent/conversations", Map.of(), token);
        String convId = created.get("id").asText();

        // multipart 上传会话图片 → assets source_kind='conversation' source_ref=convId
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        org.springframework.util.LinkedMultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.core.io.ByteArrayResource(new byte[]{1, 2, 3, 4}) {
            @Override
            public String getFilename() {
                return "cat.png";
            }
        });
        form.add("conversationId", convId);
        form.add("source", "uploaded");
        form.add("description", "一只猫");
        ResponseEntity<String> upload = rest.postForEntity(base() + "/api/nova/agent/images",
                new HttpEntity<>(form, headers), String.class);
        assertThat(upload.getStatusCode().value()).isEqualTo(201);
        JsonNode uploadedNode = parse(upload);
        String assetId = uploadedNode.get("assetId").asText();

        // 图片目录列表
        JsonNode images = get("/api/nova/agent/conversations/" + convId + "/images", token);
        assertThat(images.get("items").size()).isEqualTo(1);
        assertThat(images.get("items").get(0).get("sourceRef").asText()).isEqualTo(convId);

        // 图片字节可访问（GET 用纯 Bearer 头，避免 multipart Content-Type 触发解析）
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(token);
        ResponseEntity<String> file = rest.exchange(base() + "/api/nova/agent/images/" + assetId,
                HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
        assertThat(file.getStatusCode().value()).isEqualTo(200);
    }

    private HttpHeaders headersWithJson(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return headers;
    }
}
