package com.nova.studio.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2 T2.1/T2.6 — settings package: allowlist validation, whole-package
 * upsert/invalidate, numeric reads for limit.*, and the legacy localStorage
 * import (settings only — the legacy model branch is 下线, WIN-33).
 */
class SettingsServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SettingsRepository repository;
    private SettingsCache cache;
    private SettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(SettingsRepository.class);
        cache = new SettingsCache();
        service = new SettingsService(repository, cache, MAPPER);
    }

    @Test
    void putRejectsNonAllowlistedKeys() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("theme", "dark");  // UI pref — must stay in the browser
        assertThatThrownBy(() -> service.putAll(USER_ID, body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不允许的设置项: theme");
        verify(repository, never()).upsert(any(), anyString(), anyString(), anyString());
    }

    @Test
    void putAcceptsAllowlistedNamespacesAndInvalidatesCache() {
        when(repository.findAllByUser(USER_ID)).thenReturn(Map.of());
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode workbench = MAPPER.createObjectNode();
        workbench.put("model", "gpt-image-2");
        body.set("workbench.t2i", workbench);
        body.put("limit.maxQueueSize", 300);

        service.putAll(USER_ID, body);

        verify(repository).upsert(USER_ID, "workbench.t2i", workbench.toString(), "json");
        verify(repository).upsert(USER_ID, "limit.maxQueueSize", "300", "json");
        // cache invalidated → next read hits the DB again
        assertThat(cache.get(USER_ID)).isNull();
    }

    @Test
    void getIntReadsLimitFromSettingsWithFallback() {
        when(repository.findAllByUser(USER_ID)).thenReturn(Map.of(
                "limit.maxQueueSize", "300",
                "limit.retryAfterSeconds", "15"));
        assertThat(service.getInt(USER_ID, "limit.maxQueueSize", 200)).isEqualTo(300);
        assertThat(service.getInt(USER_ID, "limit.retryAfterSeconds", 30)).isEqualTo(15);
        assertThat(service.getInt(USER_ID, "limit.maxRequestsPerIp", 20)).isEqualTo(20);
    }

    @Test
    void getAllParsesJsonValues() {
        when(repository.findAllByUser(USER_ID)).thenReturn(Map.of(
                "workbench.t2i", "{\"model\":\"gpt-image-2\"}",
                "agent.webSearch", "true"));
        Map<String, Object> all = service.getAll(USER_ID);
        assertThat(all.get("workbench.t2i")).isEqualTo(Map.of("model", "gpt-image-2"));
        assertThat(service.getBoolean(USER_ID, "agent.webSearch", false)).isTrue();
    }

    @Test
    void importLegacyImportsSettingsOnly_modelsBranchOffline() {
        ObjectNode legacy = MAPPER.createObjectNode();
        ObjectNode registry = MAPPER.createObjectNode();
        tools.jackson.databind.node.ArrayNode imageModels = MAPPER.createArrayNode().add(imageModel("img_1", "sk-legacy-key-1234"));
        tools.jackson.databind.node.ArrayNode textModels = MAPPER.createArrayNode().add(textModel("txt_1", "sk-text-key"));
        registry.set("imageModels", imageModels);
        registry.set("textModels", textModels);
        ObjectNode defaults = MAPPER.createObjectNode();
        defaults.put("textToImage", "img_1");
        defaults.put("agent", "txt_1");
        defaults.put("promptOptimize", "nonexistent");
        registry.set("defaults", defaults);
        legacy.set("nova-model-registry", registry);
        legacy.set("nova-t2i-settings", MAPPER.createObjectNode().put("model", "img_1"));
        legacy.put("nova-agent-web-search", true);

        var summary = service.importLegacy(USER_ID, legacy);

        // WIN-33: 旧 models 表逻辑冻结 — 模型导入分支下线：不写 models，也不再写
        // registry.defaults（其引用的 legacy 模型 id 已无意义），但 settings 导入保留。
        assertThat(summary.get("modelsCreated")).isEqualTo(0);
        assertThat(summary.get("modelsImport")).isEqualTo("deprecated");
        assertThat(summary.get("settingsWritten")).isEqualTo(2);
        verify(repository, never()).upsert(eq(USER_ID), eq("registry.defaults"), anyString(), anyString());
        verify(repository).upsert(USER_ID, "workbench.t2i", "{\"model\":\"img_1\"}", "json");
        verify(repository).upsert(USER_ID, "agent.webSearch", "true", "json");
    }

    private ObjectNode imageModel(String id, String apiKey) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("id", id);
        m.put("protocol", "openai");
        m.put("name", "GPT Image 2");
        m.put("modelId", "gpt-image-2");
        m.put("baseUrl", "https://api.openai.com");
        m.put("apiKey", apiKey);
        m.put("builtinPreset", "gpt-image-2");
        m.put("maxRefImages", 4);
        m.put("maxOutputSize", "4K");
        m.put("supportsAdvancedParams", true);
        return m;
    }

    private ObjectNode textModel(String id, String apiKey) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("id", id);
        m.put("protocol", "openai-responses");
        m.put("name", "GPT 5.4 Mini");
        m.put("modelId", "gpt-5.4-mini");
        m.put("baseUrl", "https://api.openai.com");
        m.put("apiKey", apiKey);
        m.put("note", "note");
        return m;
    }
}
