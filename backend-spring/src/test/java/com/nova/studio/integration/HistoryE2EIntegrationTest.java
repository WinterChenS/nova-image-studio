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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIN-41 T12/T13 E2E — 统一历史（histories 表）：
 * 反推双槽/草稿（每用户至多一条）、GIF 状态机（非法迁移 409）、成品 multipart 上传、
 * 统一历史列表/删除、属主隔离（越权 404）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class HistoryE2EIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private final RestTemplate rest = createRestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private String token;
    private String username;

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
        username = "hist_e2e_" + UUID.randomUUID().toString().substring(0, 8);
        token = registerAndLogin(username);
    }

    @AfterEach
    void tearDown() {
        String userId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM users WHERE username = ?", String.class, username);
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM histories WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM assets WHERE user_id = ? AND source_kind IN ('gif','reverse-prompt')", userId);
        }
        jdbcTemplate.update("DELETE FROM users WHERE username = ?", username);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private String registerAndLogin(String name) {
        postJson("/api/auth/register", Map.of("username", name, "password", "secret123"), null);
        JsonNode resp = postJson("/api/auth/login", Map.of("username", name, "password", "secret123"), null);
        return resp.get("token").asText();
    }

    private JsonNode postJson(String path, Object body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) headers.setBearerAuth(bearer);
        ResponseEntity<String> resp = rest.postForEntity(base() + path, new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("POST %s 应成功: %s", path, resp.getBody()).isTrue();
        return parse(resp);
    }

    private JsonNode send(String method, String path, Object body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) headers.setBearerAuth(bearer);
        ResponseEntity<String> resp = rest.exchange(base() + path, HttpMethod.valueOf(method),
                new HttpEntity<>(body, headers), String.class);
        return parse(resp);
    }

    private JsonNode parse(ResponseEntity<String> resp) {
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应解析失败: " + resp.getBody(), e);
        }
    }

    @Test
    void reverseDualSlotAndDraftAcrossEndpoints() {
        // 保存两条 completed → 双槽（limit=2）
        ObjectNode r1 = mapper.createObjectNode();
        r1.put("text", "第一张图的反推").put("model", "m1").put("mode", "simple");
        postJson("/api/nova/reverse/records", r1, token);
        ObjectNode r2 = mapper.createObjectNode();
        r2.put("text", "第二张图的反推").put("model", "m2").put("mode", "expert");
        postJson("/api/nova/reverse/records", r2, token);

        JsonNode slots = send("GET", "/api/nova/reverse/records?limit=2", null, token);
        assertThat(slots.get("items")).hasSize(2);
        assertThat(slots.get("items").get(0).get("payload").get("text").asText()).isEqualTo("第二张图的反推");

        // 草稿 upsert（每用户至多一条）
        ObjectNode draft = mapper.createObjectNode();
        draft.put("text", "草稿文字");
        draft.putArray("imageIds").add("asset-x");
        JsonNode saved = send("PUT", "/api/nova/reverse/draft", draft, token);
        assertThat(saved.get("status").asText()).isEqualTo("draft");
        assertThat(saved.get("imageIds").get(0).asText()).isEqualTo("asset-x");

        JsonNode got = send("GET", "/api/nova/reverse/draft", null, token);
        assertThat(got.get("draft").get("payload").get("text").asText()).isEqualTo("草稿文字");

        // 清空草稿（空 text + 空 imageIds）
        ObjectNode empty = mapper.createObjectNode();
        empty.put("text", "");
        empty.putArray("imageIds");
        JsonNode cleared = send("PUT", "/api/nova/reverse/draft", empty, token);
        assertThat(cleared.get("cleared").asBoolean()).isTrue();

        // 统一历史列表可查（type=reverse）+ 删除
        JsonNode list = send("GET", "/api/nova/histories?type=reverse", null, token);
        assertThat(list.get("items")).hasSize(2);
        String id = list.get("items").get(0).get("id").asText();
        JsonNode del = send("DELETE", "/api/nova/histories/" + id, null, token);
        assertThat(del.get("ok").asBoolean()).isTrue();
        JsonNode after = send("GET", "/api/nova/histories?type=reverse", null, token);
        assertThat(after.get("items")).hasSize(1);
    }

    @Test
    void gifStateMachineAndResultUpload() {
        ObjectNode body = mapper.createObjectNode();
        body.put("prompt", "做一张眨眼 GIF").put("model", "gpt-image-2")
                .put("loop", true).put("frameDelayMs", 120);
        JsonNode created = postJson("/api/nova/gif/jobs", body, token);
        assertThat(created.get("status").asText()).isEqualTo("idle");
        String id = created.get("id").asText();

        // idle → generating_grid（携带 taskId）
        ObjectNode patch1 = mapper.createObjectNode();
        patch1.put("status", "generating_grid").put("taskId", "task-1");
        JsonNode after1 = send("PATCH", "/api/nova/gif/jobs/" + id, patch1, token);
        assertThat(after1.get("status").asText()).isEqualTo("generating_grid");

        // generating_grid → review_grid（携带网格图 asset）
        ObjectNode patch2 = mapper.createObjectNode();
        patch2.put("status", "review_grid").put("gridImageAssetId", "grid-1");
        JsonNode after2 = send("PATCH", "/api/nova/gif/jobs/" + id, patch2, token);
        assertThat(after2.get("status").asText()).isEqualTo("review_grid");
        assertThat(after2.get("imageIds").get(0).asText()).isEqualTo("grid-1");

        // 非法迁移：review_grid → done 应 409（必须经 generating_gif）
        ObjectNode bad = mapper.createObjectNode();
        bad.put("status", "done");
        HttpHeaders badHeaders = new HttpHeaders();
        badHeaders.setContentType(MediaType.APPLICATION_JSON);
        badHeaders.setBearerAuth(token);
        ResponseEntity<String> badResp = rest.exchange(base() + "/api/nova/gif/jobs/" + id,
                HttpMethod.PATCH, new HttpEntity<>(bad, badHeaders), String.class);
        assertThat(badResp.getStatusCode().value()).isEqualTo(409);
        JsonNode rejected = parse(badResp);
        assertThat(rejected.get("error").asText()).contains("状态迁移无效");

        // review_grid → generating_gif → done
        ObjectNode patch3 = mapper.createObjectNode();
        patch3.put("status", "generating_gif");
        send("PATCH", "/api/nova/gif/jobs/" + id, patch3, token);

        // 成品 multipart 上传 → status done + 成品 asset
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        org.springframework.core.io.ByteArrayResource resource =
                new org.springframework.core.io.ByteArrayResource(new byte[]{1, 2, 3}) {
                    @Override
                    public String getFilename() {
                        return "result.gif";
                    }
                };
        form.add("file", resource);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = rest.postForEntity(base() + "/api/nova/gif/jobs/" + id + "/result",
                new HttpEntity<>(form, headers), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode done = parse(resp);
        assertThat(done.get("status").asText()).isEqualTo("done");
        assertThat(done.get("imageIds").size()).isEqualTo(2);   // grid-1 + 成品

        // 历史列表可见（type=gif）
        JsonNode list = send("GET", "/api/nova/histories?type=gif", null, token);
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("items").get(0).get("status").asText()).isEqualTo("done");
    }

    @Test
    void crossUserIsolationIs404() {
        ObjectNode body = mapper.createObjectNode();
        body.put("prompt", "A 的 GIF").put("model", "gpt-image-2");
        JsonNode created = postJson("/api/nova/gif/jobs", body, token);
        String id = created.get("id").asText();

        // 用户 B 访问 A 的记录 → 404（属主隔离 AC-10）
        String usernameB = "hist_e2e_b_" + UUID.randomUUID().toString().substring(0, 8);
        String tokenB = registerAndLogin(usernameB);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokenB);
            ResponseEntity<String> resp = rest.exchange(base() + "/api/nova/gif/jobs/" + id,
                    HttpMethod.GET, new HttpEntity<>(headers), String.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(404);
        } finally {
            String userIdB = jdbcTemplate.queryForObject(
                    "SELECT id::text FROM users WHERE username = ?", String.class, usernameB);
            if (userIdB != null) {
                jdbcTemplate.update("DELETE FROM histories WHERE user_id = ?", userIdB);
            }
            jdbcTemplate.update("DELETE FROM users WHERE username = ?", usernameB);
        }
    }
}
