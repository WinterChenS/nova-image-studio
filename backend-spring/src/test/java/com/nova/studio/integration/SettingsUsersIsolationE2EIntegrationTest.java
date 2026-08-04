package com.nova.studio.integration;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * M2 (WIN-12) E2E over the real HTTP surface (external PG, gated on DB_HOST):
 * register/login/JWT, model CRUD with masked keys, settings package, task
 * creation via server-side model resolution, and the multi-user isolation
 * acceptance (A cannot see B's models/settings/tasks; anonymous cannot create
 * tasks or read settings).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class SettingsUsersIsolationE2EIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private com.nova.studio.task.TaskRepository taskRepository;

    private MockWebServer upstream;
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        upstream = new MockWebServer();
        upstream.start();
    }

    @AfterEach
    void tearDown() throws Exception {
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

    private JsonNode put(String path, Map<String, Object> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> resp = rest.exchange(base() + path,
                org.springframework.http.HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
        return parse(resp.getBody());
    }

    private JsonNode getAuthed(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> resp = rest.exchange(base() + path,
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class);
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
    void fullAuthSettingsModelsTaskIsolationFlow() {
        String userA = "alice_" + UUID.randomUUID().toString().substring(0, 8);
        String userB = "bob_" + UUID.randomUUID().toString().substring(0, 8);
        String tokenA = registerAndLogin(userA);
        String tokenB = registerAndLogin(userB);

        // ---- me ----
        JsonNode me = getAuthed("/api/auth/me", tokenA);
        assertThat(me.get("username").asText()).isEqualTo(userA);

        // ---- create image model with plaintext key (server encrypts) ----
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("type", "image");
        model.put("protocol", "openai");
        model.put("name", "E2E Image");
        model.put("modelId", "gpt-image-2");
        model.put("baseUrl", upstream.url("/").toString());
        model.put("apiKey", "sk-e2e-secret-1234");
        model.put("builtinPreset", "gpt-image-2");
        model.put("maxRefImages", 4);
        model.put("maxOutputSize", "1K");
        model.put("supportsAdvancedParams", true);
        JsonNode createdModel = post("/api/nova/models", model, tokenA);
        assertThat(createdModel.get("apiKey").asText()).isEqualTo("sk-***1234");
        String modelId = createdModel.get("id").asText();

        // ---- isolation: B cannot see A's model ----
        JsonNode listB = getAuthed("/api/nova/models", tokenB);
        assertThat(listB.isArray()).isTrue();
        assertThat(listB.toString()).doesNotContain("E2E Image");

        // ---- settings package ----
        Map<String, Object> settings = new LinkedHashMap<>();
        Map<String, Object> t2i = new LinkedHashMap<>();
        t2i.put("model", modelId);
        settings.put("workbench.t2i", t2i);
        settings.put("limit.maxQueueSize", 250);
        JsonNode putSettings = put("/api/nova/settings", settings, tokenA);
        assertThat(putSettings.get("ok").asBoolean()).isTrue();

        JsonNode gotSettings = getAuthed("/api/nova/settings", tokenA);
        assertThat(gotSettings.get("workbench.t2i").get("model").asText()).isEqualTo(modelId);
        assertThat(gotSettings.get("limit.maxQueueSize").asInt()).isEqualTo(250);

        // B's settings are empty
        JsonNode settingsB = getAuthed("/api/nova/settings", tokenB);
        assertThat(settingsB.has("workbench.t2i")).isFalse();

        // ---- task creation via server-side modelId resolution ----
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("mode", "text-to-image");
        task.put("model", modelId);
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
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(bearer(tokenB)), String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        // A can read it
        ResponseEntity<String> asA = rest.exchange(base() + "/api/nova/tasks/" + taskId,
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(bearer(tokenA)), String.class);
        assertThat(asA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(asA.getBody()).get("status").asText()).isIn("排队中", "processing", "completed");

        // ---- anonymous boundary (Q1): no task creation, no settings ----
        assertThatThrownBy(() -> post("/api/nova/tasks", task, null))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> getAuthed("/api/nova/settings", null))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        // ---- legacy import (T2.6) ----
        Map<String, Object> legacy = new LinkedHashMap<>();
        Map<String, Object> registry = new LinkedHashMap<>();
        Map<String, Object> legacyImage = new LinkedHashMap<>();
        legacyImage.put("id", "img_legacy_1");
        legacyImage.put("protocol", "openai");
        legacyImage.put("name", "Legacy Image");
        legacyImage.put("modelId", "gpt-image-1");
        legacyImage.put("baseUrl", upstream.url("/").toString());
        legacyImage.put("apiKey", "sk-legacy-import-key");
        legacyImage.put("builtinPreset", "gpt-image-2");
        legacyImage.put("maxRefImages", 4);
        legacyImage.put("maxOutputSize", "1K");
        legacyImage.put("supportsAdvancedParams", true);
        registry.put("imageModels", java.util.List.of(legacyImage));
        registry.put("textModels", java.util.List.of());
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("textToImage", "img_legacy_1");
        registry.put("defaults", defaults);
        legacy.put("nova-model-registry", registry);
        legacy.put("nova-t2i-settings", java.util.Map.of("model", "img_legacy_1"));

        JsonNode imported = post("/api/nova/settings/import", legacy, tokenB);
        assertThat(imported.get("modelsCreated").asInt()).isEqualTo(1);

        JsonNode modelsB = getAuthed("/api/nova/models", tokenB);
        assertThat(modelsB.toString()).contains("Legacy Image");
        JsonNode settingsAfterImport = getAuthed("/api/nova/settings", tokenB);
        assertThat(settingsAfterImport.has("registry.defaults")).isTrue();
        String remappedDefault = settingsAfterImport.get("registry.defaults").get("textToImage").asText();
        assertThat(remappedDefault).isNotEqualTo("img_legacy_1");
        assertThat(remappedDefault).isNotEmpty();
        // the imported default points at a real model owned by B
        JsonNode modelsAfter = getAuthed("/api/nova/models", tokenB);
        assertThat(modelsAfter.toString()).contains(remappedDefault);
    }

    @Test
    void anonymousCanReadLegacyNullTaskButNotUserTask() {
        // register + create a task as A (owns it) — not readable anonymously
        String userA = "anoncheck_" + UUID.randomUUID().toString().substring(0, 8);
        String tokenA = registerAndLogin(userA);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("type", "image");
        model.put("protocol", "openai");
        model.put("name", "AnonCheck");
        model.put("modelId", "gpt-image-2");
        model.put("baseUrl", upstream.url("/").toString());
        model.put("apiKey", "sk-anon-check");
        model.put("builtinPreset", "gpt-image-2");
        model.put("maxRefImages", 4);
        model.put("maxOutputSize", "1K");
        model.put("supportsAdvancedParams", true);
        JsonNode createdModel = post("/api/nova/models", model, tokenA);
        String modelId = createdModel.get("id").asText();

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("mode", "text-to-image");
        task.put("model", modelId);
        task.put("prompt", "anon probe");
        task.put("outputSize", "1K");
        task.put("aspectRatio", "1:1");
        task.put("temperature", 1.0);
        task.put("parallelCount", 1);
        task.put("images", java.util.List.of());
        String taskId = post("/api/nova/tasks", task, tokenA).get("taskId").asText();

        // anonymous GET → 404 (task is user-owned; do not leak existence)
        assertThatThrownBy(() -> rest.exchange(base() + "/api/nova/tasks/" + taskId,
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void anonymousCanReadLegacyNullTask() {
        // N1 (WIN-12 复审): 匿名可读 NULL 归属（遗留/迁移）任务 — 直接插入一条
        // user_id = NULL 的任务行，匿名 GET 应返回 200 排队中（Q1 匿名只读边界）。
        String legacyTaskId = "legacy-null-" + UUID.randomUUID().toString().substring(0, 8);
        taskRepository.insertTaskAndItems(legacyTaskId, null, com.nova.studio.task.TaskRepository.STATUS_QUEUED,
                "text-to-image", "{\"mode\":\"text-to-image\",\"prompt\":\"legacy\"}",
                java.time.Instant.now().toString(), 1);
        try {
            ResponseEntity<String> resp = rest.exchange(base() + "/api/nova/tasks/" + legacyTaskId,
                    org.springframework.http.HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode task = parse(resp.getBody());
            assertThat(task.get("id").asText()).isEqualTo(legacyTaskId);
            assertThat(task.get("status").asText()).isIn("排队中", "queued");
        } finally {
            taskRepository.deleteTaskAndItems(legacyTaskId);
        }
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }
}
