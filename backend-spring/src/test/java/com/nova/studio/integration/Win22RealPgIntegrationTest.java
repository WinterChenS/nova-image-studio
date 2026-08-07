package com.nova.studio.integration;

import com.nova.studio.accountpool.AccountRepository;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.settings.CryptoService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIN-22 QA B-1/B-3/G-1 复测 — 真实 PG 下的项目/任务/素材列表与搜索路径。
 * 覆盖 QA 报告中的三个真实环境缺陷（uuid=varchar 类型错误、COUNT+ORDER BY、
 * multipart 上传 400）以及 A1–A8 主链路。仅在 {@code DB_HOST} 设置时运行
 * （CI 提供 PostgreSQL 服务；本地无 PG 自动跳过）。
 *
 * <p>WIN-34 合并适配：WIN-28 账号池化后任务创建走全局目录（ai_models）+
 * 账号池（ai_accounts），{@code model} 必须为目录 UUID（Q1 移除旧 per-user
 * apiKey/baseUrl/protocol 入参）。本测试 setUp 播种一条目录图片模型 + active
 * 账号（mock 上游返回合法 b64，任务正常完成），任务体仅传目录 UUID——
 * 被测的 WIN-22 项目隔离行为（projectId 落库/列表过滤/未分类/一键归入）不变。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class Win22RealPgIntegrationTest {

    @LocalServerPort
    int port;

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
    @Autowired
    private com.nova.studio.audit.UsageRecordService usageRecordService;

    private MockWebServer upstream;
    private UUID catalogModelId;
    private UUID accountId;

    // WIN-32: SimpleClientHttpRequestFactory 不支持 PATCH（http.client 4 也未被 Spring 7
    // 使用）——JdkClientHttpRequestFactory 基于 JDK java.net.http，GET/POST/PUT/PATCH/DELETE 全支持。
    // 错误响应不抛异常（4xx 作为普通响应返回，由各用例自行断言状态码，如跨用户 404）。
    private final RestTemplate rest = createRestTemplate();

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
    private final ObjectMapper mapper = new ObjectMapper();
    private String token;
    private String username;
    private String otherUsername;

    @BeforeEach
    void setUp() throws Exception {
        username = "win22_" + UUID.randomUUID().toString().substring(0, 8);
        registerAndLogin();
        // WIN-34: 播种目录模型 + 账号（WIN-28 账号池化后任务创建的前置）。
        // mock 上游返回合法 b64，任务正常完成（不依赖失败路径）。
        upstream = new MockWebServer();
        upstream.start();
        String b64 = java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        for (int i = 0; i < 4; i++) {
            upstream.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}"));
        }
        // 自愈：清理上次失败运行遗留的测试种子（W22E2E-*）
        jdbcTemplate.update("DELETE FROM usage_records WHERE account_id IN (SELECT id FROM ai_accounts WHERE name LIKE 'W22E2E-%')");
        jdbcTemplate.update("DELETE FROM ai_model_pricing WHERE model_id IN (SELECT id FROM ai_models WHERE name LIKE 'W22E2E%')");
        jdbcTemplate.update("DELETE FROM ai_accounts WHERE name LIKE 'W22E2E-%'");
        jdbcTemplate.update("DELETE FROM ai_models WHERE name LIKE 'W22E2E%'");
        // 显式 127.0.0.1（MockWebServer.url() 可能返回机器 hostname，跨环境不可靠）
        String mockBase = "http://127.0.0.1:" + upstream.getPort();
        catalogModelId = catalogRepository.insert("image", "openai", "W22E2E 图片模型", "gpt-image-1",
                mockBase, "{}", null, true, null);
        accountId = accountRepository.insert("W22E2E-账号", "openai", mockBase,
                cryptoService.encrypt("sk-e2e"), "[]", 100, null, null);
        accountService.invalidateCaches();
    }

    @AfterEach
    void tearDown() throws Exception {
        usageRecordService.flush();   // 异步 usage 写入先落库，再清理种子行（避免 FK 竞态）
        jdbcTemplate.update("DELETE FROM usage_records WHERE account_id IN (SELECT id FROM ai_accounts WHERE name LIKE 'W22E2E-%')");
        jdbcTemplate.update("DELETE FROM ai_model_pricing WHERE model_id IN (SELECT id FROM ai_models WHERE name LIKE 'W22E2E%')");
        jdbcTemplate.update("DELETE FROM ai_accounts WHERE name LIKE 'W22E2E-%'");
        jdbcTemplate.update("DELETE FROM ai_models WHERE name LIKE 'W22E2E%'");
        if (catalogModelId != null) {
            catalogRepository.deleteById(catalogModelId);
        }
        if (accountId != null) {
            accountRepository.updateStatus(accountId, "deleted");
        }
        if (upstream != null) {
            upstream.shutdown();
        }
        // 清理测试用户（级联删除其项目/素材/任务）——含跨用户用例的第二个用户（E-3）
        if (otherUsername != null && !otherUsername.equals(username)) {
            jdbcTemplate.update("DELETE FROM users WHERE username = ?", otherUsername);
        }
        jdbcTemplate.update("DELETE FROM users WHERE username = ?", username);
    }

    private void registerAndLogin() {
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("username", username);
        reg.put("password", "secret123");
        postJson("/api/auth/register", reg);
        Map<String, Object> login = new LinkedHashMap<>();
        login.put("username", username);
        login.put("password", "secret123");
        JsonNode loginResp = postJson("/api/auth/login", login);
        token = loginResp.get("token").asText();
    }

    private JsonNode postJson(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> resp = rest.postForEntity(url(path), new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("POST %s 应成功: %s", path, resp.getBody()).isTrue();
        return read(resp);
    }

    private JsonNode get(String path) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> resp = rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("GET %s 应成功: %s", path, resp.getBody()).isTrue();
        return read(resp);
    }

    private JsonNode read(ResponseEntity<String> resp) {
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ===== A1/B-1：项目列表（默认项目懒创建） =====

    @Test
    void projectListCreatesDefaultProjectAndFiltersCorrectly() {
        JsonNode projects = get("/api/nova/projects");
        assertThat(projects.isArray()).isTrue();
        assertThat(projects.size()).isGreaterThanOrEqualTo(1);
        assertThat(projects.get(0).get("name").asText()).isEqualTo("默认项目");

        // 创建第二个项目
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("name", "海报组");
        create.put("description", "win22 测试");
        JsonNode created = postJson("/api/nova/projects", create);
        String projectId = created.get("id").asText();
        assertThat(projectId).isNotBlank();

        // 归档后默认列表不可见，includeArchived 可见
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        rest.exchange(url("/api/nova/projects/" + projectId), HttpMethod.PUT,
                new HttpEntity<>(Map.of("archived", true), headers), String.class);
        assertThat(get("/api/nova/projects").size()).isEqualTo(1);
        assertThat(get("/api/nova/projects?includeArchived=true").size()).isEqualTo(2);
    }

    // ===== A2/B-1：任务携带 projectId + 列表过滤 =====

    @Test
    void taskListFiltersByProjectAndUnclassified() throws Exception {
        String projectId = defaultProjectId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "text-to-image");
        body.put("prompt", "a cat in a hat");
        body.put("outputSize", "1K");
        body.put("aspectRatio", "1:1");
        body.put("temperature", 1.0);
        body.put("model", catalogModelId.toString());   // WIN-28: 目录 UUID（账号池化）
        body.put("parallelCount", 1);
        body.put("images", java.util.List.of());
        body.put("projectId", projectId);

        JsonNode created = postJson("/api/nova/tasks", body);
        String taskId = created.get("taskId").asText();
        awaitTerminal(taskId);   // WIN-34: 等任务终态，避免 worker 在 tearDown 后写 usage 触发 FK 竞态

        // GET /api/nova/tasks?projectId= → 列表（B-1：user_id 为 UUID 列，String 绑定曾 400）
        JsonNode list = get("/api/nova/tasks?projectId=" + projectId);
        assertThat(list.get("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(list.get("items").get(0).get("id").asText()).isEqualTo(taskId);

        // GET /api/nova/tasks?projectId=__unclassified__ → 未分类列表（应为空，任务已归入）
        JsonNode unclassified = get("/api/nova/tasks?projectId=__unclassified__");
        assertThat(unclassified.get("items").isArray()).isTrue();

        // 单查响应含 projectId（F-4）
        JsonNode single = get("/api/nova/tasks/" + taskId);
        assertThat(single.has("projectId")).isTrue();
        assertThat(single.get("projectId").asText()).isEqualTo(projectId);
    }

    // ===== A3：任务一键归入 =====

    @Test
    void taskAssignProjectMovesFromUnclassified() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "text-to-image");
        body.put("prompt", "legacy task");
        body.put("outputSize", "1K");
        body.put("aspectRatio", "1:1");
        body.put("temperature", 1.0);
        body.put("model", catalogModelId.toString());   // WIN-28: 目录 UUID（账号池化）
        body.put("parallelCount", 1);
        body.put("images", java.util.List.of());
        // 不带 projectId → 兜底默认项目（ADR-17），先验证含 projectId

        JsonNode created = postJson("/api/nova/tasks", body);
        String taskId = created.get("taskId").asText();
        awaitTerminal(taskId);   // WIN-34: 等任务终态，避免 worker 在 tearDown 后写 usage 触发 FK 竞态
        JsonNode single = get("/api/nova/tasks/" + taskId);
        assertThat(single.get("projectId").asText()).isEqualTo(defaultProjectId());

        // 归入第二个项目
        Map<String, Object> proj = new LinkedHashMap<>();
        proj.put("name", "目标项目");
        String targetId = postJson("/api/nova/projects", proj).get("id").asText();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        ResponseEntity<String> patch = rest.exchange(url("/api/nova/tasks/" + taskId + "/project"),
                HttpMethod.PATCH, new HttpEntity<>(Map.of("projectId", targetId), headers), String.class);
        assertThat(patch.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(get("/api/nova/tasks/" + taskId).get("projectId").asText()).isEqualTo(targetId);
        assertThat(get("/api/nova/tasks?projectId=" + targetId).get("total").asLong()).isGreaterThanOrEqualTo(1);
    }

    // ===== B-2/B-3/A5/A6/A7：素材 multipart 上传 + 列表/搜索 =====

    @Test
    void assetMultipartUploadAndSearchWork() {
        String projectId = defaultProjectId();

        // multipart 上传（B-2 回归：带 charset 的 multipart 不得 400）
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(new byte[]{1, 2, 3, 4}) {
            @Override
            public String getFilename() {
                return "测试图.png";
            }
        });
        form.add("projectId", projectId);
        form.add("sourceKind", "upload");
        form.add("name", "测试图");
        form.add("tags", "win22,回归");
        ResponseEntity<String> uploadResp = rest.postForEntity(url("/api/nova/assets"),
                new HttpEntity<>(form, headers), String.class);
        assertThat(uploadResp.getStatusCode().is2xxSuccessful())
                .as("multipart 上传应成功: %s", uploadResp.getBody()).isTrue();
        String assetId = read(uploadResp).get("id").asText();
        assertThat(assetId).isNotBlank();

        // 列表（B-3 回归：COUNT 不得携带 ORDER BY）
        JsonNode list = get("/api/nova/assets?projectId=" + projectId);
        assertThat(list.get("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(list.get("items").get(0).get("id").asText()).isEqualTo(assetId);

        // 来源筛选 + 搜索 + 标签 + 排序（A6/A7）
        assertThat(get("/api/nova/assets?projectId=" + projectId + "&source=upload").get("total").asLong())
                .isGreaterThanOrEqualTo(1);
        assertThat(get("/api/nova/assets?projectId=" + projectId + "&q=测试").get("total").asLong())
                .isGreaterThanOrEqualTo(1);
        assertThat(get("/api/nova/assets?projectId=" + projectId + "&tag=win22").get("total").asLong())
                .isGreaterThanOrEqualTo(1);
        assertThat(get("/api/nova/assets?projectId=" + projectId + "&sort=oldest").get("total").asLong())
                .isGreaterThanOrEqualTo(1);

        // 未分类列表（空）
        assertThat(get("/api/nova/assets?projectId=__unclassified__").get("items").isArray()).isTrue();

        // 对象读取
        ResponseEntity<String> fileResp = rest.exchange(url("/api/nova/assets/" + assetId + "/file"),
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(fileResp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    // ===== B-4：JSON 文本素材创建（tags JSONB 第二条命中路径） =====

    @Test
    void textAssetCreateAndSearchWorks() {
        String projectId = defaultProjectId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", "一只戴帽子的猫");
        body.put("name", "提示词-A");
        body.put("projectId", projectId);
        body.put("tags", java.util.List.of("win22", "text"));
        body.put("sourceKind", "manual");
        JsonNode created = postJson("/api/nova/assets", body);
        String assetId = created.get("id").asText();
        assertThat(assetId).isNotBlank();
        assertThat(created.get("kind").asText()).isEqualTo("text");
        assertThat(created.get("content").asText()).isEqualTo("一只戴帽子的猫");

        // 列表可见 + 标签筛选可命中（JSONB ?? 查询）
        assertThat(get("/api/nova/assets?projectId=" + projectId + "&tag=win22").get("total").asLong())
                .isGreaterThanOrEqualTo(1);
        assertThat(get("/api/nova/assets?projectId=" + projectId + "&q=提示词").get("total").asLong())
                .isGreaterThanOrEqualTo(1);
    }

    // ===== A4：跨用户隔离（404） =====

    @Test
    void crossUserAccessReturns404() {
        // 用户 A 的项目
        Map<String, Object> proj = new LinkedHashMap<>();
        proj.put("name", "A 的项目");
        String projectId = postJson("/api/nova/projects", proj).get("id").asText();

        // 用户 B 访问 A 的项目 → 404（ProjectController 无 GET 单查，A4 正确验证路径是
        // PUT/DELETE —— WIN-32 按 QA 报告第四节修正：GET → PUT 跨用户）
        otherUsername = "win22b_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("username", otherUsername);
        reg.put("password", "secret123");
        postJson("/api/auth/register", reg);
        Map<String, Object> login = new LinkedHashMap<>();
        login.put("username", otherUsername);
        login.put("password", "secret123");
        String otherToken = postJson("/api/auth/login", login).get("token").asText();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(otherToken);
        ResponseEntity<String> resp = rest.exchange(url("/api/nova/projects/" + projectId),
                HttpMethod.PUT, new HttpEntity<>(Map.of("name", "改名"), headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    // ===== A13：存储健康 =====

    @Test
    void storageHealthReturnsMode() {
        JsonNode health = get("/api/nova/storage/health");
        assertThat(health.has("mode")).isTrue();
        assertThat(health.get("mode").asText()).isIn("minio", "disk");
        assertThat(health.has("bucketExists")).isTrue();
        assertThat(health.has("lastCheckedAt")).isTrue();
    }

    private String defaultProjectId() {
        JsonNode projects = get("/api/nova/projects");
        return projects.get(0).get("id").asText();
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** WIN-34: 轮询任务至终态（completed/failed），并等异步 usage 行落库
     *  （ref_type='task'），确保 tearDown 删用户/种子前 usage 写入已完成，
     *  避免 FK 竞态。 */
    private void awaitTerminal(String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            HttpHeaders headers = authHeaders();
            ResponseEntity<String> resp = rest.exchange(url("/api/nova/tasks/" + taskId),
                    HttpMethod.GET, new HttpEntity<>(headers), String.class);
            assertThat(resp.getStatusCode().is2xxSuccessful()).as("GET task 应成功").isTrue();
            String status = read(resp).get("status").asText();
            if ("completed".equals(status) || "failed".equals(status)) {
                awaitUsageFlushed(taskId);
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("task did not finish within 20s: " + taskId);
    }

    /** WIN-34: 等 usage_records 中该任务的异步写入落库（worker 在任务终态后才入队）。 */
    private void awaitUsageFlushed(String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM usage_records WHERE ref_type = 'task' AND ref_id = ?",
                    Integer.class, taskId);
            if (n != null && n > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("usage not flushed for task " + taskId);
    }
}
