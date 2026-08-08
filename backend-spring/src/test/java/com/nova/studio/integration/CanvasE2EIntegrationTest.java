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
 * WIN-40 T5 E2E — 画布后端 API（真实 PG + JWT）：项目 CRUD、整文档 PUT（version 自增）、
 * 软删回收站恢复、属主隔离（AC-10）、画布图片上传（assets source_kind='canvas'）。
 * AC-3 基础（F3 写点可经 API 完成，软删回收站可恢复）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class CanvasE2EIntegrationTest {

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
        username = "canvas_e2e_" + UUID.randomUUID().toString().substring(0, 8);
        otherUsername = "canvas_e2e_" + UUID.randomUUID().toString().substring(0, 8);
        token = registerAndLogin(username);
        otherToken = registerAndLogin(otherUsername);
    }

    @AfterEach
    void tearDown() {
        String userId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM users WHERE username = ?", String.class, username);
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM canvas_projects WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM assets WHERE user_id = ? AND source_kind = 'canvas'", userId);
        }
        String otherId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM users WHERE username = ?", String.class, otherUsername);
        if (otherId != null) {
            jdbcTemplate.update("DELETE FROM canvas_projects WHERE user_id = ?", otherId);
            jdbcTemplate.update("DELETE FROM assets WHERE user_id = ? AND source_kind = 'canvas'", otherId);
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

    @Test
    void canvasLifecycleWithVersionAndRecycleBin() {
        // 新建项目
        JsonNode created = postJson("/api/nova/canvas/projects", Map.of("title", "我的画布"), token);
        String projectId = created.get("id").asText();
        assertThat(created.get("version").asLong()).isEqualTo(1);

        // 整文档 PUT（version 自增）
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("nodes", java.util.List.of(Map.of("id", "n1", "image", Map.of("assetId", "a1"))));
        doc.put("connections", java.util.List.of());
        doc.put("viewport", Map.of("x", 10, "y", 20, "k", 1.5));
        JsonNode saved = send("PUT", "/api/nova/canvas/projects/" + projectId, doc, token);
        assertThat(saved.get("version").asLong()).isEqualTo(2);
        assertThat(saved.get("nodes").get(0).get("id").asText()).isEqualTo("n1");

        // 重命名（PATCH）
        JsonNode renamed = send("PATCH", "/api/nova/canvas/projects/" + projectId,
                Map.of("title", "重命名画布"), token);
        assertThat(renamed.get("title").asText()).isEqualTo("重命名画布");

        // 列表
        JsonNode list = get("/api/nova/canvas/projects", token);
        assertThat(list.get("items").size()).isGreaterThanOrEqualTo(1);

        // 软删 → 回收站 → 恢复
        send("DELETE", "/api/nova/canvas/projects/" + projectId, null, token);
        JsonNode normal = get("/api/nova/canvas/projects", token);
        assertThat(normal.get("items").size()).isZero();
        JsonNode recycle = get("/api/nova/canvas/projects?includeDeleted=true", token);
        assertThat(recycle.get("items").size()).isGreaterThanOrEqualTo(1);
        postJson("/api/nova/canvas/projects/" + projectId + "/restore", Map.of(), token);
        JsonNode restored = get("/api/nova/canvas/projects/" + projectId, token);
        assertThat(restored.get("deletedAt").isNull()).isTrue();
    }

    @Test
    void canvasOwnershipIsolation() {
        JsonNode created = postJson("/api/nova/canvas/projects", Map.of("title", "A 的画布"), token);
        String projectId = created.get("id").asText();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(otherToken);
        ResponseEntity<String> resp = rest.exchange(base() + "/api/nova/canvas/projects/" + projectId,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void canvasImageUploadReturnsAssetId() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        org.springframework.util.LinkedMultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.core.io.ByteArrayResource(new byte[]{1, 2, 3, 4}) {
            @Override
            public String getFilename() {
                return "bg.png";
            }
        });
        form.add("name", "背景图");
        ResponseEntity<String> upload = rest.postForEntity(base() + "/api/nova/canvas/images",
                new HttpEntity<>(form, headers), String.class);
        assertThat(upload.getStatusCode().value()).isEqualTo(201);
        JsonNode node = parse(upload);
        assertThat(node.has("assetId")).isTrue();
        assertThat(node.get("sourceKind").asText()).isEqualTo("canvas");
        // 素材库默认不含工作态类型（excludeWorking 默认 true）
        JsonNode library = get("/api/nova/assets?excludeWorking=true", token);
        for (JsonNode item : library.get("items")) {
            assertThat(item.get("sourceKind").asText()).isNotIn("canvas", "conversation");
        }
    }
}
