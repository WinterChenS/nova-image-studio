package com.nova.studio.integration;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.nova.studio.auth.UserEntity;
import com.nova.studio.auth.UserMapper;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T3.1 (WIN-13) E2E over the real HTTP surface (external PG, gated on DB_HOST):
 * prompts/blacklist served from the DB (seeded from file at startup), admin API
 * authorization (401 anonymous / 403 non-admin / admin CRUD), and public
 * endpoint parity ({@code [{title, content, type}]} / {@code {keywords: [...]}}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class GalleryDbE2EIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private UserMapper userMapper;

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private String promotedUsername;

    @AfterEach
    void cleanup() {
        if (promotedUsername != null) {
            userMapper.delete(new UpdateWrapper<UserEntity>().eq("username", promotedUsername));
        }
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private JsonNode exchange(String path, HttpMethod method, Map<String, Object> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> resp = rest.exchange(base() + path, method,
                new HttpEntity<>(body, headers), String.class);
        return parse(resp.getBody());
    }

    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("bad json: " + raw, e);
        }
    }

    private String registerAndLogin(String username) {
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("username", username);
        reg.put("password", "secret123");
        exchange("/api/auth/register", HttpMethod.POST, reg, null);
        JsonNode body = exchange("/api/auth/login", HttpMethod.POST, reg, null);
        return body.get("token").asText();
    }

    private String registerAdminAndLogin(String username) {
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("username", username);
        reg.put("password", "secret123");
        exchange("/api/auth/register", HttpMethod.POST, reg, null);
        // promote to admin directly (AdminBootstrap covers the env-driven path)
        userMapper.update(null, new UpdateWrapper<UserEntity>()
                .eq("username", username).set("role", "admin").set("updated_at", Instant.now()));
        promotedUsername = username;
        JsonNode body = exchange("/api/auth/login", HttpMethod.POST, reg, null);
        return body.get("token").asText();
    }

    // ===== public endpoints serve DB data =====

    @Test
    void publicPromptsAndBlacklistServeDb() {
        JsonNode prompts = exchange("/api/nova/prompts", HttpMethod.GET, null, null);
        assertThat(prompts.isArray()).isTrue();
        assertThat(prompts.size()).isGreaterThan(0);
        JsonNode first = prompts.get(0);
        assertThat(first.has("title")).isTrue();
        assertThat(first.has("content")).isTrue();
        assertThat(first.get("type").asInt()).isIn(1, 2);

        JsonNode blacklist = exchange("/api/nova/blacklist", HttpMethod.GET, null, null);
        assertThat(blacklist.has("keywords")).isTrue();
        assertThat(blacklist.get("keywords").isArray()).isTrue();
    }

    // ===== admin authorization =====

    @Test
    void anonymousCannotAccessAdminApi() {
        assertThatThrownBy(() -> exchange("/api/nova/admin/prompts", HttpMethod.GET, null, null))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void nonAdminGets403OnAdminApi() {
        String token = registerAndLogin("user_" + UUID.randomUUID().toString().substring(0, 8));
        assertThatThrownBy(() -> exchange("/api/nova/admin/prompts", HttpMethod.GET, null, token))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ===== admin CRUD round trip =====

    @Test
    void adminPromptCrudRoundTrip() {
        String adminToken = registerAdminAndLogin("admin_" + UUID.randomUUID().toString().substring(0, 8));

        Map<String, Object> create = new LinkedHashMap<>();
        create.put("title", "E2E 测试提示 " + UUID.randomUUID().toString().substring(0, 6));
        create.put("content", "E2E 内容");
        create.put("type", 2);
        String createdTitle = (String) create.get("title");
        JsonNode created = exchange("/api/nova/admin/prompts", HttpMethod.POST, create, adminToken);
        assertThat(created.get("title").asText()).isEqualTo(createdTitle);
        String promptId = created.get("id").asText();

        // public endpoint reflects the DB row
        JsonNode publicPrompts = exchange("/api/nova/prompts", HttpMethod.GET, null, null);
        boolean visible = false;
        var it = publicPrompts.iterator();
        while (it.hasNext()) {
            JsonNode p = it.next();
            if (createdTitle.equals(p.get("title").asText())) {
                visible = true;
                break;
            }
        }
        assertThat(visible).isTrue();

        // update
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("title", createdTitle + " (改)");
        update.put("enabled", false);
        JsonNode updated = exchange("/api/nova/admin/prompts/" + promptId, HttpMethod.PUT, update, adminToken);
        assertThat(updated.get("title").asText()).endsWith("(改)");
        assertThat(updated.get("enabled").asBoolean()).isFalse();

        // delete
        JsonNode deleted = exchange("/api/nova/admin/prompts/" + promptId, HttpMethod.DELETE, null, adminToken);
        assertThat(deleted.get("ok").asBoolean()).isTrue();
        var deleteIt = exchange("/api/nova/prompts", HttpMethod.GET, null, null).iterator();
        while (deleteIt.hasNext()) {
            JsonNode p = deleteIt.next();
            assertThat(p.get("title").asText()).isNotEqualTo(createdTitle);
        }
    }

    @Test
    void adminBlacklistRoundTrip() {
        String adminToken = registerAdminAndLogin("admin_" + UUID.randomUUID().toString().substring(0, 8));
        String keyword = "E2E违禁词" + UUID.randomUUID().toString().substring(0, 4);

        Map<String, Object> add = new LinkedHashMap<>();
        add.put("keyword", keyword);
        JsonNode created = exchange("/api/nova/admin/blacklist", HttpMethod.POST, add, adminToken);
        assertThat(created.get("keyword").asText()).isEqualTo(keyword);

        // public blacklist reflects it
        JsonNode publicBlacklist = exchange("/api/nova/blacklist", HttpMethod.GET, null, null);
        boolean visible = false;
        var it = publicBlacklist.get("keywords").iterator();
        while (it.hasNext()) {
            JsonNode k = it.next();
            if (keyword.equals(k.asText())) {
                visible = true;
            }
        }
        assertThat(visible).isTrue();

        // duplicate → 409
        assertThatThrownBy(() -> exchange("/api/nova/admin/blacklist", HttpMethod.POST, add, adminToken))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        // delete by keyword
        JsonNode deleted = exchange("/api/nova/admin/blacklist/" + keyword, HttpMethod.DELETE, null, adminToken);
        assertThat(deleted.get("ok").asBoolean()).isTrue();
        var delIt = exchange("/api/nova/blacklist", HttpMethod.GET, null, null).get("keywords").iterator();
        while (delIt.hasNext()) {
            JsonNode k = delIt.next();
            assertThat(k.asText()).isNotEqualTo(keyword);
        }
    }
}
