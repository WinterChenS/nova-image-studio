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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIN-40 T7 E2E — 迁移框架：Agent/画布批量导入幂等（唯一键去重，FR-7.2/7.3）、
 * upload-image 图片分批上传（→ assets，返回 assetId 供引用改写）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class MigrationE2EIntegrationTest {

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
        username = "mig_e2e_" + UUID.randomUUID().toString().substring(0, 8);
        token = registerAndLogin(username);
    }

    @AfterEach
    void tearDown() {
        String userId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM users WHERE username = ?", String.class, username);
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM conversation_messages WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM conversations WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM canvas_projects WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM histories WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM assets WHERE user_id = ? AND source_kind IN ('canvas','conversation')", userId);
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

    private JsonNode parse(ResponseEntity<String> resp) {
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应解析失败: " + resp.getBody(), e);
        }
    }

    @Test
    void agentImportIsIdempotent() {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode conversations = body.putArray("conversations");
        ObjectNode conv = conversations.addObject();
        conv.put("id", "mig-conv-1").put("title", "迁移会话");
        ArrayNode messages = conv.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("id", "mig-msg-1").put("role", "user").put("text", "你好，这是迁移数据");
        msg.putArray("imageIds").add("mig-asset-1");

        // 第一次导入 → created=1
        JsonNode first = postJson("/api/nova/migration/agent/import", body, token);
        assertThat(first.get("created").asInt()).isEqualTo(1);
        assertThat(first.get("skipped").asInt()).isZero();

        // 第二次导入（相同唯一键）→ skipped=1，created=0（幂等 FR-7.2/7.3）
        JsonNode second = postJson("/api/nova/migration/agent/import", body, token);
        assertThat(second.get("created").asInt()).isZero();
        assertThat(second.get("skipped").asInt()).isEqualTo(1);

        Long convCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE id = 'mig-conv-1'", Long.class);
        assertThat(convCount).isEqualTo(1);
    }

    @Test
    void canvasImportIsIdempotentWithNodeRefs() {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode projects = body.putArray("projects");
        ObjectNode project = projects.addObject();
        project.put("id", "mig-canvas-1").put("title", "迁移画布");
        ArrayNode nodes = project.putArray("nodes");
        ObjectNode node = nodes.addObject();
        node.put("id", "n1").put("type", "image");
        ObjectNode image = node.putObject("image");
        image.put("assetId", "mig-asset-9");
        project.putArray("connections");

        JsonNode first = postJson("/api/nova/migration/canvas/import", body, token);
        assertThat(first.get("created").asInt()).isEqualTo(1);

        JsonNode second = postJson("/api/nova/migration/canvas/import", body, token);
        assertThat(second.get("skipped").asInt()).isEqualTo(1);

        // 节点引用已落库（imageRef=assetId，ADR-35）
        String nodesJson = jdbcTemplate.queryForObject(
                "SELECT nodes::text FROM canvas_projects WHERE id = 'mig-canvas-1'", String.class);
        assertThat(nodesJson).contains("mig-asset-9");
    }

    @Test
    void uploadImageReturnsAssetIdForReferenceRewrite() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        org.springframework.util.LinkedMultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.core.io.ByteArrayResource(new byte[]{9, 8, 7, 6}) {
            @Override
            public String getFilename() {
                return "mig.png";
            }
        });
        form.add("sourceKind", "canvas");
        ResponseEntity<String> resp = rest.postForEntity(base() + "/api/nova/migration/upload-image",
                new HttpEntity<>(form, headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        JsonNode node = parse(resp);
        assertThat(node.has("assetId")).isTrue();
        assertThat(node.get("sourceKind").asText()).isEqualTo("canvas");
    }

    @Test
    void reverseImportWritesHistories() {
        String historyId = "mig-rev-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode body = mapper.createObjectNode();
        ArrayNode items = body.putArray("items");
        ObjectNode item = items.addObject();
        item.put("id", historyId).put("type", "reverse").put("status", "completed");
        item.putObject("payload").put("text", "一只猫，赛博朋克");
        item.putArray("imageIds").add("mig-asset-2");

        JsonNode first = postJson("/api/nova/migration/reverse/import", body, token);
        assertThat(first.get("created").asInt()).isEqualTo(1);
        JsonNode second = postJson("/api/nova/migration/reverse/import", body, token);
        assertThat(second.get("skipped").asInt()).isEqualTo(1);
    }
}
