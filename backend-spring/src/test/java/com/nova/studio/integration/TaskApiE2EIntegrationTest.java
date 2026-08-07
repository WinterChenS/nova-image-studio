package com.nova.studio.integration;

import com.nova.studio.accountpool.AccountRepository;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.audit.UsageRecordRepository;
import com.nova.studio.settings.CryptoService;
import com.nova.studio.storage.ImageStorageService;
import com.nova.studio.task.TaskService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5/T6/T7/T9/T10 (WIN-28) — full task/proxy lifecycle over the real HTTP
 * surface with a mocked upstream on the ACCOUNT POOL: catalog model + account
 * seeded via repositories, task created with a catalog UUID → scheduler selects
 * the account → completed; usage_records written (task level, idempotent A20);
 * audit query + CSV export (A9); text proxy (non-stream + SSE) via pool.
 *
 * <p>Runs only when {@code DB_HOST} is set (external PG, same gating as the
 * other integration tests).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nova.ws.heartbeat-interval-ms=1000",
                "nova.ws.pong-grace-ms=500"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class TaskApiE2EIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private TaskService taskService;
    @Autowired
    private ImageStorageService imageStorageService;
    @Autowired
    private CatalogModelRepository catalogRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private com.nova.studio.accountpool.AccountService accountService;
    @Autowired
    private CryptoService cryptoService;
    @Autowired
    private UsageRecordRepository usageRecordRepository;
    @Autowired
    private com.nova.studio.audit.UsageRecordService usageRecordService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockWebServer upstream;
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private String token;

    private UUID imageCatalogId;
    private UUID textCatalogId;
    private UUID imageAccountId;
    private UUID textAccountId;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new MockWebServer();
        upstream.start();

        // 自愈：清理上次失败运行遗留的测试种子（E2E-* / 无账号模型）
        jdbcTemplate.update("DELETE FROM usage_records WHERE account_id IN (SELECT id FROM ai_accounts WHERE name LIKE 'E2E-%' OR name LIKE 'ab-diff-%') OR ref_id IN ('audit-1','idem-task-1')");
        jdbcTemplate.update("DELETE FROM ai_model_pricing WHERE model_id IN (SELECT id FROM ai_models WHERE name LIKE 'E2E%')");
        jdbcTemplate.update("DELETE FROM ai_accounts WHERE name LIKE 'E2E-%' OR name LIKE 'ab-diff-%'");
        jdbcTemplate.update("DELETE FROM ai_models WHERE name LIKE 'E2E%' OR name LIKE 'ab-diff-%' OR name LIKE '无账号模型'");

        // 注册普通用户
        String username = "e2e_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("username", username);
        reg.put("password", "secret123");
        post("http://localhost:" + port + "/api/auth/register", reg);
        Map<String, Object> login = new LinkedHashMap<>();
        login.put("username", username);
        login.put("password", "secret123");
        JsonNode loginResp = post("http://localhost:" + port + "/api/auth/login", login);
        token = loginResp.get("token").asText();

        // 种子：目录模型 + 账号池（指向 mock 上游）
        imageCatalogId = catalogRepository.insert("image", "openai", "E2E 图片模型", "gpt-image-1",
                upstream.url("/").toString(), "{}", null, true, null);
        textCatalogId = catalogRepository.insert("text", "openai-chat-completions", "E2E 文本模型", "gpt-4o",
                upstream.url("/").toString(), "{}", null, true, null);
        imageAccountId = accountRepository.insert("E2E-账号", "openai", upstream.url("/").toString(),
                cryptoService.encrypt("sk-e2e-image-key"), "[]", 100, null, null, null);
        textAccountId = accountRepository.insert("E2E-文本账号", "openai-chat-completions", upstream.url("/").toString(),
                cryptoService.encrypt("sk-e2e-text-key"), "[]", 100, null, null, null);
        accountService.invalidateCaches();
    }

    @AfterEach
    void tearDown() throws Exception {
        usageRecordService.flush();   // 异步 usage 写入先落库，再清理种子行（避免 FK 竞态）
        if (imageCatalogId != null) {
            catalogRepository.deleteById(imageCatalogId);
        }
        if (textCatalogId != null) {
            catalogRepository.deleteById(textCatalogId);
        }
        if (imageAccountId != null) {
            accountRepository.updateStatus(imageAccountId, "deleted");
        }
        if (textAccountId != null) {
            accountRepository.updateStatus(textAccountId, "deleted");
        }
        upstream.shutdown();
    }

    private Map<String, Object> taskBody(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "text-to-image");
        body.put("prompt", prompt);
        body.put("model", imageCatalogId.toString());
        body.put("outputSize", "1K");
        body.put("aspectRatio", "1:1");
        body.put("temperature", 1.0);
        body.put("parallelCount", 1);
        body.put("images", java.util.List.of());
        return body;
    }

    @Test
    void taskLifecycleCompletesViaPoolAndWritesUsage() throws Exception {
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}) + "\"}]}"));

        JsonNode created = post("http://localhost:" + port + "/api/nova/tasks", taskBody("a red apple"));
        String taskId = created.get("taskId").asText();
        assertThat(taskId).isNotBlank();

        JsonNode task = awaitCompleted(taskId);
        assertThat(task.get("status").asText()).isEqualTo("completed");
        assertThat(task.get("result").get("images").get(0).asText()).isEqualTo("URL:/api/nova/images/" + taskId + "/0");

        // 上游收到的是账号池账号的 Key 与目录模型名
        RecordedRequest req = upstream.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/v1/images/generations");
        JsonNode reqBody = mapper.readTree(req.getBody().readUtf8());
        assertThat(reqBody.get("model").asText()).isEqualTo("gpt-image-1");

        // T7: usage_records 写入（task 级一条）
        usageRecordService.flush();
        assertThat(usageRecordRepository.countByRef("task", taskId)).isEqualTo(1);
        Map<String, Object> usage = jdbcTemplate.queryForMap(
                "SELECT * FROM usage_records WHERE ref_type='task' AND ref_id=?", taskId);
        assertThat(usage.get("status")).isEqualTo("success");
        assertThat(usage.get("images")).isEqualTo(1);
        assertThat(usage.get("account_id")).isNotNull();
        assertThat(usage.get("model_id")).isNotNull();

        taskService.deleteTask(taskId);
        imageStorageService.deleteTaskImageFiles(taskId);
    }

    @Test
    void usageWriteIsIdempotent() {
        // 同 (ref_type, ref_id) 二次写入 → ON CONFLICT DO NOTHING，仍一条（A20）
        jdbcTemplate.update("DELETE FROM usage_records WHERE ref_type='task' AND ref_id='idem-task-1'");
        var rec = new com.nova.studio.audit.UsageRecordService.UsageRecord(
                null, imageAccountId, imageCatalogId, "openai", "image", "task", "idem-task-1",
                "success", null, null, 1, "CNY", 10L);
        assertThat(usageRecordService.recordSync(rec)).isEqualTo(1);
        assertThat(usageRecordService.recordSync(rec)).isZero();   // 幂等：第二次 no-op
        assertThat(usageRecordRepository.countByRef("task", "idem-task-1")).isEqualTo(1);
        jdbcTemplate.update("DELETE FROM usage_records WHERE ref_type='task' AND ref_id='idem-task-1'");
    }

    @Test
    void auditQueryAndExportIncludeSeededUsage() throws Exception {
        // 造一条 usage 数据
        jdbcTemplate.update("""
                INSERT INTO usage_records (user_id, account_id, model_id, protocol, req_type, ref_type, ref_id,
                                           status, input_tokens, output_tokens, images, cost, currency, duration_ms, created_at)
                VALUES (?, ?, ?, 'openai', 'image', 'task', 'audit-1', 'success', 100, 200, 1, 0.8, 'CNY', 500, now())
                """, userId(), imageAccountId, imageCatalogId);

        // 提权为 admin（测试内直接改库；生产经 AdminBootstrap —— M2 双写 user_roles + 缓存失效 A14）
        jdbcTemplate.update("UPDATE users SET role='admin' WHERE id=?", userId());
        jdbcTemplate.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT u.id, r.id FROM users u, roles r
                WHERE u.id = ? AND r.code = 'admin'
                ON CONFLICT DO NOTHING
                """, userId());
        Thread.sleep(1100); // ADR-29: 权限缓存 ≤1s 失效
        String adminToken = loginToken();

        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + port + "/api/nova/admin/usage?reqType=image&page=1&size=50",
                HttpMethod.GET, new HttpEntity<>(bearer(adminToken)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(resp.getBody());
        assertThat(body.get("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(body.get("summary").get("requestCount").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode first = body.get("items").get(0);
        assertThat(first.get("username").asText()).isNotBlank();

        // CSV 导出
        ResponseEntity<byte[]> csvResp = rest.exchange(
                "http://localhost:" + port + "/api/nova/admin/usage/export?reqType=image",
                HttpMethod.GET, new HttpEntity<>(bearer(adminToken)), byte[].class);
        assertThat(csvResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String csv = new String(csvResp.getBody(), StandardCharsets.UTF_8);
        assertThat(csv).contains("时间,用户");
        assertThat(csv).contains("success");
        assertThat(csvResp.getHeaders().getFirst("Content-Disposition")).contains("usage-export.csv");

        // 非 admin 直调 → 403
        assertThatThrownBy(() -> rest.exchange(
                "http://localhost:" + port + "/api/nova/admin/usage",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        jdbcTemplate.update("DELETE FROM usage_records WHERE ref_id='audit-1'");
    }

    @Test
    void textProxyNonStreamViaPool() throws Exception {
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelId", textCatalogId.toString());
        body.put("stream", false);
        body.put("messages", java.util.List.of(Map.of("role", "user", "content", "hello")));

        JsonNode resp = post("http://localhost:" + port + "/api/nova/proxy/text", body);
        assertThat(resp.get("choices").get(0).get("message").get("content").asText()).isEqualTo("hi");

        RecordedRequest req = upstream.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/v1/chat/completions");
        JsonNode forwarded = mapper.readTree(req.getBody().readUtf8());
        assertThat(forwarded.has("apiKey")).isFalse();
        assertThat(forwarded.has("modelId")).isFalse();
        assertThat(forwarded.get("messages").get(0).get("content").asText()).isEqualTo("hello");
    }

    @Test
    void textProxySsePassthroughViaPool() throws Exception {
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n"
                        + "data: [DONE]\n\n"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelId", textCatalogId.toString());
        body.put("stream", true);
        body.put("messages", java.util.List.of(Map.of("role", "user", "content", "hello")));

        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + port + "/api/nova/proxy/text",
                HttpMethod.POST, new HttpEntity<>(body, bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).contains("text/event-stream");
        assertThat(resp.getBody()).contains("data: [DONE]");
    }

    @Test
    void taskRejectedWhenNoUsableAccount() {
        // 再建一个无账号的目录模型
        UUID orphan = catalogRepository.insert("image", "grok", "无账号模型", "grok-imagine",
                upstream.url("/").toString(), "{}", null, true, null);
        try {
            Map<String, Object> body = taskBody("x");
            body.put("model", orphan.toString());
            RestTemplate lenient = lenientRest();
            ResponseEntity<String> resp = lenient.exchange("http://localhost:" + port + "/api/nova/tasks",
                    HttpMethod.POST, new HttpEntity<>(body, bearer(token)), String.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            assertThat(resp.getBody()).contains("可用账号");
        } finally {
            catalogRepository.deleteById(orphan);
        }
    }

    @Test
    void invalidCatalogModelReturns400() {
        Map<String, Object> body = taskBody("x");
        body.put("model", "not-a-uuid");
        RestTemplate lenient = lenientRest();
        ResponseEntity<String> resp = lenient.exchange("http://localhost:" + port + "/api/nova/tasks",
                HttpMethod.POST, new HttpEntity<>(body, bearer(token)), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("未找到模型配置");
    }

    // ===== helpers =====

    private UUID userId() {
        return UUID.fromString(jwtSubject(token));
    }

    private String jwtSubject(String jwt) {
        // 免验签解出 sub（测试内取 userId）
        try {
            String payload = jwt.split("\\.")[1];
            byte[] bytes = java.util.Base64.getUrlDecoder().decode(payload);
            return mapper.readTree(bytes).get("sub").asText();
        } catch (Exception e) {
            throw new AssertionError("无法解析 JWT sub", e);
        }
    }

    private String loginToken() {
        // 重新登录以刷新 role（提权后）
        String username = jdbcTemplate.queryForObject(
                "SELECT username FROM users WHERE id=?", String.class, userId());
        Map<String, Object> login = new LinkedHashMap<>();
        login.put("username", username);
        login.put("password", "secret123");
        JsonNode resp = post("http://localhost:" + port + "/api/auth/login", login);
        return resp.get("token").asText();
    }

    private RestTemplate lenientRest() {
        RestTemplate lenient = new RestTemplate();
        lenient.setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(java.net.URI url, HttpMethod method,
                                    org.springframework.http.client.ClientHttpResponse response) {
            }
        });
        return lenient;
    }

    private JsonNode post(String url, Object body) {
        ResponseEntity<String> resp = rest.postForEntity(url, new HttpEntity<>(body, bearer(token)), String.class);
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private JsonNode get(String url) {
        ResponseEntity<String> resp = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private HttpHeaders bearer(String t) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (t != null) {
            headers.setBearerAuth(t);
        }
        return headers;
    }

    private JsonNode awaitCompleted(String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode task = get("http://localhost:" + port + "/api/nova/tasks/" + taskId);
            String status = task.get("status").asText();
            if ("completed".equals(status) || "failed".equals(status)) {
                return task;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("task did not finish within 20s: " + taskId);
    }
}
