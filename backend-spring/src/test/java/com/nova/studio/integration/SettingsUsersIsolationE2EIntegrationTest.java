package com.nova.studio.integration;

import com.nova.studio.accountpool.AccountRepository;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.settings.CryptoService;
import okhttp3.mockwebserver.MockWebServer;
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
 * WIN-28 E2E over the real HTTP surface (external PG, gated on DB_HOST):
 * register/login/JWT, the global catalog read (no per-user keys, A2), settings
 * package isolation, task creation via catalog UUID + account pool, and the
 * multi-user isolation acceptance (A cannot read B's tasks; anonymous cannot
 * create tasks or read settings; NULL-owner legacy tasks stay anonymously
 * readable until M2's gate closing T16).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class SettingsUsersIsolationE2EIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private com.nova.studio.task.TaskRepository taskRepository;
    @Autowired
    private com.nova.studio.audit.UsageRecordService usageRecordService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired
    private CatalogModelRepository catalogRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private com.nova.studio.accountpool.AccountService accountService;
    @Autowired
    private CryptoService cryptoService;

    private MockWebServer upstream;
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID catalogModelId;
    private UUID accountId;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new MockWebServer();
        upstream.start();
        // 默认 mock 上游响应（每个任务的图片生成各消费一条；全类共用 2 条）
        String b64 = java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        for (int i = 0; i < 2; i++) {
            upstream.enqueue(new okhttp3.mockwebserver.MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}"));
        }
        // 自愈：清理上次失败运行遗留的测试种子（E2E-*）
        jdbcTemplate.update("DELETE FROM usage_records WHERE account_id IN (SELECT id FROM ai_accounts WHERE name LIKE 'E2E-%')");
        jdbcTemplate.update("DELETE FROM ai_model_pricing WHERE model_id IN (SELECT id FROM ai_models WHERE name LIKE 'E2E%')");
        jdbcTemplate.update("DELETE FROM ai_accounts WHERE name LIKE 'E2E-%'");
        jdbcTemplate.update("DELETE FROM ai_models WHERE name LIKE 'E2E%'");
        catalogModelId = catalogRepository.insert("image", "openai", "E2E 图片模型", "gpt-image-1",
                upstream.url("/").toString(), "{}", null, true, null);
        accountId = accountRepository.insert("E2E-账号", "openai", upstream.url("/").toString(),
                cryptoService.encrypt("sk-e2e-isolation"), "[]", 100, null, null);
        accountService.invalidateCaches();
    }

    @AfterEach
    void tearDown() throws Exception {
        usageRecordService.flush();   // 异步 usage 写入先落库，再清理种子行（避免 FK 竞态）
        if (catalogModelId != null) {
            catalogRepository.deleteById(catalogModelId);
        }
        if (accountId != null) {
            accountRepository.updateStatus(accountId, "deleted");
        }
        upstream.shutdown();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private JsonNode post(String path, Map<String, Object> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> resp = rest.postForEntity(base() + path,
                new HttpEntity<>(body, headers), String.class);
        return parse(resp.getBody());
    }

    private JsonNode getAuthed(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> resp = rest.exchange(base() + path,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return parse(resp.getBody());
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("bad json: " + body, e);
        }
    }

    private String registerAndLogin(String username) {
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("username", username);
        reg.put("password", "secret123");
        JsonNode created = post("/api/auth/register", reg, null);
        assertThat(created.has("id")).isTrue();

        Map<String, Object> login = new LinkedHashMap<>();
        login.put("username", username);
        login.put("password", "secret123");
        JsonNode body = post("/api/auth/login", login, null);
        assertThat(body.has("token")).isTrue();
        return body.get("token").asText();
    }

    @Test
    void fullAuthSettingsTaskIsolationFlow() throws Exception {
        String userA = "alice_" + UUID.randomUUID().toString().substring(0, 8);
        String userB = "bob_" + UUID.randomUUID().toString().substring(0, 8);
        String tokenA = registerAndLogin(userA);
        String tokenB = registerAndLogin(userB);

        // ---- me ----
        JsonNode me = getAuthed("/api/auth/me", tokenA);
        assertThat(me.get("username").asText()).isEqualTo(userA);

        // ---- 全局目录（无 per-user Key；两个用户看到同一目录，无 apiKey 字段）----
        JsonNode catalogA = getAuthed("/api/nova/models", tokenA);
        JsonNode catalogB = getAuthed("/api/nova/models", tokenB);
        assertThat(catalogA.isArray()).isTrue();
        assertThat(catalogA.toString()).contains("E2E 图片模型");
        assertThat(catalogA.toString()).doesNotContain("apiKey");
        assertThat(catalogA.toString()).contains("\"available\":true");
        assertThat(catalogA.toString()).isEqualTo(catalogB.toString());   // 全局目录

        // ---- settings package isolation ----
        Map<String, Object> settings = new LinkedHashMap<>();
        Map<String, Object> t2i = new LinkedHashMap<>();
        t2i.put("model", catalogModelId.toString());
        settings.put("workbench.t2i", t2i);
        settings.put("limit.maxQueueSize", 250);
        JsonNode putSettings = putSettings(tokenA, settings);
        assertThat(putSettings.get("ok").asBoolean()).isTrue();

        JsonNode gotSettings = getAuthed("/api/nova/settings", tokenA);
        assertThat(gotSettings.get("workbench.t2i").get("model").asText()).isEqualTo(catalogModelId.toString());
        assertThat(gotSettings.get("limit.maxQueueSize").asInt()).isEqualTo(250);

        JsonNode settingsB = getAuthed("/api/nova/settings", tokenB);
        assertThat(settingsB.has("workbench.t2i")).isFalse();

        // ---- task creation via catalog UUID + account pool ----
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("mode", "text-to-image");
        task.put("model", catalogModelId.toString());
        task.put("prompt", "e2e cat");
        task.put("outputSize", "1K");
        task.put("aspectRatio", "1:1");
        task.put("temperature", 1.0);
        task.put("parallelCount", 1);
        task.put("images", java.util.List.of());
        JsonNode createdTask = post("/api/nova/tasks", task, tokenA);
        assertThat(createdTask.has("taskId")).isTrue();
        String taskId = createdTask.get("taskId").asText();

        // ---- isolation: B cannot read A's task -------
        assertThatThrownBy(() -> rest.exchange(base() + "/api/nova/tasks/" + taskId,
                HttpMethod.GET, new HttpEntity<>(bearer(tokenB)), String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        // A can read it
        ResponseEntity<String> asA = rest.exchange(base() + "/api/nova/tasks/" + taskId,
                HttpMethod.GET, new HttpEntity<>(bearer(tokenA)), String.class);
        assertThat(asA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(asA.getBody()).get("status").asText()).isIn("排队中", "processing", "completed");

        // 等任务终态（避免 worker 在 tearDown 清理种子后写 usage 引发 FK 竞态）
        awaitTerminal(taskId, tokenA);

        // ---- anonymous boundary (Q1): no task creation, no settings ----
        assertThatThrownBy(() -> post("/api/nova/tasks", task, null))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> getAuthed("/api/nova/settings", null))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void anonymousCanReadLegacyNullTaskButNotUserTask() throws Exception {
        String userA = "anoncheck_" + UUID.randomUUID().toString().substring(0, 8);
        String tokenA = registerAndLogin(userA);

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("mode", "text-to-image");
        task.put("model", catalogModelId.toString());
        task.put("prompt", "anon probe");
        task.put("outputSize", "1K");
        task.put("aspectRatio", "1:1");
        task.put("temperature", 1.0);
        task.put("parallelCount", 1);
        task.put("images", java.util.List.of());
        String taskId = post("/api/nova/tasks", task, tokenA).get("taskId").asText();
        awaitTerminal(taskId, tokenA);

        // anonymous GET → 404 (task is user-owned; do not leak existence)
        assertThatThrownBy(() -> rest.exchange(base() + "/api/nova/tasks/" + taskId,
                HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void anonymousCanReadLegacyNullTask() {
        // N1 (WIN-12 复审): 匿名可读 NULL 归属（遗留/迁移）任务 — 直接插入一条
        // user_id = NULL 的任务行，匿名 GET 应返回 200 排队中（Q1 匿名只读边界，M2 T16 收口）。
        String legacyTaskId = "legacy-null-" + UUID.randomUUID().toString().substring(0, 8);
        taskRepository.insertTaskAndItems(legacyTaskId, null, null, com.nova.studio.task.TaskRepository.STATUS_QUEUED,
                "text-to-image", "{\"mode\":\"text-to-image\",\"prompt\":\"legacy\"}",
                java.time.Instant.now().toString(), 1);
        try {
            ResponseEntity<String> resp = rest.exchange(base() + "/api/nova/tasks/" + legacyTaskId,
                    HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode task = parse(resp.getBody());
            assertThat(task.get("id").asText()).isEqualTo(legacyTaskId);
            assertThat(task.get("status").asText()).isIn("排队中", "queued");
        } finally {
            taskRepository.deleteTaskAndItems(legacyTaskId);
        }
    }

    private JsonNode putSettings(String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> resp = rest.exchange(base() + "/api/nova/settings",
                HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
        return parse(resp.getBody());
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private void awaitTerminal(String taskId, String token) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<String> resp = rest.exchange(base() + "/api/nova/tasks/" + taskId,
                    HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
            String status = parse(resp.getBody()).get("status").asText();
            if ("completed".equals(status) || "failed".equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("task did not finish within 20s: " + taskId);
    }
}
