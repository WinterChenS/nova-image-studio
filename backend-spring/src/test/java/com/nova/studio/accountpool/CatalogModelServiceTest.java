package com.nova.studio.accountpool;

import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2 (WIN-28) — catalog model service: admin CRUD (protocol whitelist,
 * UNIQUE(protocol, model_id) conflict → 409), delete cascades account
 * model_scope cleanup (ADR-28), and the public catalog read marks
 * {@code available} per enabled/usable accounts (A1/A2/A5).
 */
class CatalogModelServiceTest {

    private CatalogModelRepository repository;
    private AccountService accountService;
    private CatalogModelService service;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID ADMIN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MODEL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        repository = mock(CatalogModelRepository.class);
        accountService = mock(AccountService.class);
        service = new CatalogModelService(repository, accountService, MAPPER);
    }

    private CatalogModelRepository.Row row(String type, String protocol, String modelId, boolean enabled) {
        return new CatalogModelRepository.Row(MODEL_ID, type, protocol, "模型", modelId,
                "https://api.example.com/v1", "{}", null, enabled, ADMIN,
                Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"));
    }

    private tools.jackson.databind.node.ObjectNode validBody(String type, String protocol) {
        tools.jackson.databind.node.ObjectNode body = MAPPER.createObjectNode();
        body.put("type", type);
        body.put("protocol", protocol);
        body.put("name", "测试模型");
        body.put("modelId", "gpt-image-1");
        body.put("baseUrl", "https://api.example.com/v1");
        return body;
    }

    // ===== create =====

    @Test
    void createValidImageModel() {
        when(repository.insert(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(MODEL_ID);
        when(repository.findById(MODEL_ID)).thenReturn(Optional.of(row("image", "openai", "gpt-image-1", true)));

        var dto = service.create(ADMIN, validBody("image", "openai"));

        assertThat(dto.get("id")).isEqualTo(MODEL_ID.toString());
        assertThat(dto.get("type")).isEqualTo("image");
        assertThat(dto.get("enabled")).isEqualTo(true);
    }

    @Test
    void createRejectsUnknownProtocol() {
        assertThatThrownBy(() -> service.create(ADMIN, validBody("image", "bogus")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("协议");
    }

    @Test
    void createRejectsTextProtocolForImageType() {
        // image 协议白名单：google/openai/grok；google-gemini 属 text
        assertThatThrownBy(() -> service.create(ADMIN, validBody("image", "google-gemini")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("协议");
    }

    @Test
    void createRejectsDuplicateProtocolModelId() {
        when(repository.existsProtocolModelId("openai", "gpt-image-1")).thenReturn(true);
        assertThatThrownBy(() -> service.create(ADMIN, validBody("image", "openai")))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("MODEL_TAKEN");
                });
    }

    // ===== delete cascades account scope (ADR-28) =====

    @Test
    void deleteRemovesModelFromAccountScopes() {
        when(repository.deleteById(MODEL_ID)).thenReturn(1);
        service.delete(ADMIN, MODEL_ID);
        verify(accountService).removeModelFromScopes(MODEL_ID);
    }

    @Test
    void deleteUnknownModelReturns404() {
        when(repository.deleteById(MODEL_ID)).thenReturn(0);
        assertThatThrownBy(() -> service.delete(ADMIN, MODEL_ID))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(404));
        verify(accountService, never()).removeModelFromScopes(any());
    }

    // ===== public catalog read (A2/A5) =====

    @Test
    void publicCatalogContainsOnlyEnabledModels() {
        var disabled = row("image", "openai", "gpt-image-1", false);
        when(repository.findAllEnabled()).thenReturn(List.of(disabled));
        when(accountService.hasCandidate(any())).thenReturn(true);

        var dto = service.listPublicCatalog();

        assertThat(dto).isEmpty(); // disabled 不出现
    }

    @Test
    void publicCatalogMarksAvailability() {
        var enabled = row("image", "openai", "gpt-image-1", true);
        when(repository.findAllEnabled()).thenReturn(List.of(enabled));
        when(accountService.hasCandidate(enabled)).thenReturn(true);
        assertThat(service.listPublicCatalog().getFirst().get("available")).isEqualTo(true);

        when(accountService.hasCandidate(enabled)).thenReturn(false);
        assertThat(service.listPublicCatalog().getFirst().get("available")).isEqualTo(false);
    }

    // ===== admin list includes disabled =====

    @Test
    void adminListIncludesDisabled() {
        when(repository.findAll()).thenReturn(List.of(row("image", "openai", "gpt-image-1", false)));
        assertThat(service.listAdmin()).hasSize(1);
    }
}
