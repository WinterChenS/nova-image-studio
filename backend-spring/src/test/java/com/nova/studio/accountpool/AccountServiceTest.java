package com.nova.studio.accountpool;

import com.nova.studio.audit.AuditLogService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
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
 * T3 (WIN-28) — account pool service: admin CRUD (key AES-GCM encrypted via
 * {@link CryptoService}, masked responses), model_scope existence validation
 * (R1), soft delete (ADR-28), pause/resume/recover status transitions, and
 * candidate checks for the catalog availability flag (A1/A4).
 */
class AccountServiceTest {

    private AccountRepository repository;
    private CatalogModelRepository catalogRepository;
    private CryptoService crypto;
    private AccountService service;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID ADMIN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MODEL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        repository = mock(AccountRepository.class);
        catalogRepository = mock(CatalogModelRepository.class);
        crypto = mock(CryptoService.class);
        when(crypto.encrypt(anyString())).thenAnswer(inv -> "v1:iv:" + inv.getArgument(0));
        when(crypto.decrypt(anyString())).thenAnswer(inv -> {
            String enc = inv.getArgument(0);
            return enc == null ? null : enc.replaceFirst("^v1:iv:", "");
        });
        service = new AccountService(repository, catalogRepository, crypto, MAPPER, null,
                mock(AuditLogService.class));
    }

    private AccountRepository.Row row(String status, String protocol, String scopeJson, String healthJson) {
        return new AccountRepository.Row(ACCOUNT_ID, "主账号", protocol, "https://api.example.com/v1",
                "v1:iv:sk-secret-1234", scopeJson, status, 100, null, healthJson, null, ADMIN,
                Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"));
    }

    private ObjectNode validBody() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", "主账号");
        body.put("protocol", "openai");
        body.put("baseUrl", "https://api.example.com/v1");
        body.put("apiKey", "sk-secret-1234");
        return body;
    }

    // ===== create =====

    @Test
    void createEncryptsKeyAndReturnsMasked() {
        when(repository.insert(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(ACCOUNT_ID);
        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.of(row("active", "openai", "[]", "{}")));

        var dto = service.create(ADMIN, validBody());

        assertThat((String) dto.get("apiKey")).isEqualTo("sk-***1234");
        assertThat((String) dto.get("apiKey")).doesNotContain("sk-secret-1234");
        verify(crypto).encrypt("sk-secret-1234");
    }

    @Test
    void createRejectsMissingKey() {
        ObjectNode body = validBody();
        body.remove("apiKey");
        assertThatThrownBy(() -> service.create(ADMIN, body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API Key");
    }

    @Test
    void createRejectsUnknownProtocol() {
        ObjectNode body = validBody();
        body.put("protocol", "bogus");
        assertThatThrownBy(() -> service.create(ADMIN, body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("协议");
    }

    @Test
    void createValidatesModelScopeIdsExist() {
        ObjectNode body = validBody();
        ArrayNode scope = body.putArray("modelScope");
        scope.add(MODEL_ID.toString());
        when(catalogRepository.findById(MODEL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(ADMIN, body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型作用域");
    }

    @Test
    void createAcceptsEmptyScope() {
        when(repository.insert(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(ACCOUNT_ID);
        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.of(row("active", "openai", "[]", "{}")));

        var dto = service.create(ADMIN, validBody());

        assertThat(dto.get("id")).isEqualTo(ACCOUNT_ID.toString());
    }

    // ===== update keeps masked key (no wipe) =====

    @Test
    void updateWithMaskedKeyKeepsCiphertext() {
        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.of(row("active", "openai", "[]", "{}")));
        when(repository.update(any(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(1);

        ObjectNode body = validBody();
        body.put("apiKey", "sk-***1234"); // masked → keep existing
        service.update(ADMIN, ACCOUNT_ID, body);

        verify(crypto, never()).encrypt("sk-***1234");
        verify(repository).update(any(), anyString(), anyString(), anyString(), eq2("v1:iv:sk-secret-1234"), anyString(), any(), any(), any());
    }

    // ===== status transitions (A4 / ADR-28) =====

    @Test
    void softDeleteSetsDeletedStatus() {
        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.of(row("active", "openai", "[]", "{}")));
        when(repository.updateStatus(ACCOUNT_ID, "deleted")).thenReturn(1);

        service.delete(ADMIN, ACCOUNT_ID);

        verify(repository).updateStatus(ACCOUNT_ID, "deleted");
    }

    @Test
    void pauseAndResume() {
        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.of(row("active", "openai", "[]", "{}")));
        when(repository.updateStatus(ACCOUNT_ID, "paused")).thenReturn(1);
        service.pause(ACCOUNT_ID);
        verify(repository).updateStatus(ACCOUNT_ID, "paused");

        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.of(row("paused", "openai", "[]", "{}")));
        when(repository.updateStatus(ACCOUNT_ID, "active")).thenReturn(1);
        service.resume(ACCOUNT_ID);
        verify(repository).updateStatus(ACCOUNT_ID, "active");
    }

    @Test
    void recoverFromBrokenResetsHealth() {
        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.of(
                row("broken", "openai", "[]", "{\"consecutive_failures\":5}")));
        when(repository.updateStatus(ACCOUNT_ID, "active")).thenReturn(1);

        service.recover(ACCOUNT_ID);

        verify(repository).updateStatus(ACCOUNT_ID, "active");
        verify(repository).updateHealth(eq2(ACCOUNT_ID), anyString());
    }

    @Test
    void deleteUnknownAccountReturns404() {
        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(ADMIN, ACCOUNT_ID))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(404));
    }

    // ===== candidates (A5) =====

    @Test
    void hasCandidateRequiresProtocolMatch() {
        var model = modelRow("openai", "gpt-image-1");
        when(repository.findAllByStatus("active")).thenReturn(List.of(
                row("active", "google", "[]", "{}")));
        assertThat(service.hasCandidate(model)).isFalse();
    }

    @Test
    void hasCandidateHonorsModelScope() {
        var model = modelRow("openai", "gpt-image-1");
        when(repository.findAllByStatus("active")).thenReturn(List.of(
                row("active", "openai", "[\"" + UUID.randomUUID() + "\"]", "{}")));
        assertThat(service.hasCandidate(model)).isFalse();
    }

    @Test
    void hasCandidateTrueWhenScopeEmpty() {
        var model = modelRow("openai", "gpt-image-1");
        when(repository.findAllByStatus("active")).thenReturn(List.of(
                row("active", "openai", "[]", "{}")));
        assertThat(service.hasCandidate(model)).isTrue();
    }

    @Test
    void hasCandidateExcludesCoolingDownAccounts() {
        var model = modelRow("openai", "gpt-image-1");
        String health = "{\"cooldown_until\":\"" + Instant.now().plusSeconds(600) + "\"}";
        when(repository.findAllByStatus("active")).thenReturn(List.of(
                row("active", "openai", "[]", health)));
        assertThat(service.hasCandidate(model)).isFalse();
    }

    @Test
    void hasCandidateIgnoresExpiredCooldown() {
        var model = modelRow("openai", "gpt-image-1");
        String health = "{\"cooldown_until\":\"" + Instant.now().minusSeconds(10) + "\"}";
        when(repository.findAllByStatus("active")).thenReturn(List.of(
                row("active", "openai", "[]", health)));
        assertThat(service.hasCandidate(model)).isTrue();
    }

    // ===== scope cleanup on catalog delete (ADR-28) =====

    @Test
    void removeModelFromScopesStripsIdFromAllAccounts() {
        when(repository.listAll()).thenReturn(List.of(
                row("active", "openai", "[\"" + MODEL_ID + "\",\"00000000-0000-0000-0000-000000000009\"]", "{}")));
        when(repository.updateScope(any(), anyString())).thenReturn(1);

        service.removeModelFromScopes(MODEL_ID);

        verify(repository).updateScope(any(), anyString());
    }

    private static CatalogModelRepository.Row modelRow(String protocol, String modelId) {
        return new CatalogModelRepository.Row(MODEL_ID, "image", protocol, "模型", modelId,
                "https://api.example.com/v1", "{}", null, true, ADMIN,
                Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"));
    }

    /** Unambiguous ArgumentMatcher alias — plain eq clashes with static import none. */
    private static <T> T eq2(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    @SuppressWarnings("unused")
    private static BigDecimal price(String v) {
        return new BigDecimal(v);
    }
}
