package com.nova.studio.accountpool;

import com.nova.studio.audit.AuditLogService;
import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T9 (WIN-28) — pricing service: per-model dual-rate table (B3:
 * per_request_price + price_per_token), cost snapshot formula
 * {@code cost = 单次价 + tokens × 每 token 单价}, and the UNIQUE(model_id,
 * currency) upsert semantics (A8).
 */
class PricingServiceTest {

    private PricingRepository repository;
    private CatalogModelRepository catalogRepository;
    private PricingService service;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID ADMIN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MODEL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        repository = mock(PricingRepository.class);
        catalogRepository = mock(CatalogModelRepository.class);
        service = new PricingService(repository, catalogRepository, MAPPER, mock(AuditLogService.class));
    }

    private ObjectNode body(String perRequest, String perToken) {
        ObjectNode b = MAPPER.createObjectNode();
        b.put("modelId", MODEL_ID.toString());
        if (perRequest != null) {
            b.put("perRequestPrice", perRequest);
        }
        if (perToken != null) {
            b.put("pricePerToken", perToken);
        }
        return b;
    }

    // ===== upsert =====

    @Test
    void upsertCreatesOrUpdatesOnModelCurrency() {
        when(catalogRepository.findById(MODEL_ID)).thenReturn(Optional.of(modelRow()));
        when(repository.upsert(any(), anyString(), any(), any(), any())).thenReturn(1);

        service.upsert(ADMIN, body("0.1", "0.002"));

        verify(repository).upsert(any(), org.mockito.ArgumentMatchers.eq("CNY"),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("0.1")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("0.002")), any());
    }

    @Test
    void upsertRejectsUnknownModel() {
        when(catalogRepository.findById(MODEL_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upsert(ADMIN, body("0.1", "0.002")))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(404));
    }

    @Test
    void upsertRejectsNegativePrices() {
        when(catalogRepository.findById(MODEL_ID)).thenReturn(Optional.of(modelRow()));
        assertThatThrownBy(() -> service.upsert(ADMIN, body("-1", "0.002")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为负");
    }

    // ===== cost snapshot (B3 / A8) =====

    @Test
    void computeCostAddsPerRequestPlusTokensTimesPerToken() {
        when(repository.findByModelAndCurrency(MODEL_ID, "CNY")).thenReturn(Optional.of(pricingRow("0.5", "0.001")));

        BigDecimal cost = service.computeCost(MODEL_ID, 100L, 200L, "CNY");

        // 0.5 + (100+200) × 0.001 = 0.8
        assertThat(cost).isEqualByComparingTo("0.8");
    }

    @Test
    void computeCostWithNoTokensFallsBackToPerRequest() {
        when(repository.findByModelAndCurrency(MODEL_ID, "CNY")).thenReturn(Optional.of(pricingRow("0.5", "0.001")));

        BigDecimal cost = service.computeCost(MODEL_ID, null, null, "CNY");

        assertThat(cost).isEqualByComparingTo("0.5");
    }

    @Test
    void computeCostWithoutPricingRowIsZero() {
        when(repository.findByModelAndCurrency(MODEL_ID, "CNY")).thenReturn(Optional.empty());
        assertThat(service.computeCost(MODEL_ID, 10L, 10L, "CNY")).isEqualByComparingTo("0");
    }

    @Test
    void computeCostWithNullPerTokenIgnoresTokenDimension() {
        when(repository.findByModelAndCurrency(MODEL_ID, "CNY")).thenReturn(Optional.of(pricingRow("0.5", null)));
        assertThat(service.computeCost(MODEL_ID, 100L, 200L, "CNY")).isEqualByComparingTo("0.5");
    }

    private static PricingRepository.Row pricingRow(String perRequest, String perToken) {
        return new PricingRepository.Row(UUID.randomUUID(), MODEL_ID, "CNY",
                perRequest == null ? null : new BigDecimal(perRequest),
                perToken == null ? null : new BigDecimal(perToken),
                null, ADMIN, Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"));
    }

    private static CatalogModelRepository.Row modelRow() {
        return new CatalogModelRepository.Row(MODEL_ID, "image", "openai", "模型", "gpt-image-1",
                "https://api.example.com/v1", "{}", null, true, ADMIN,
                Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"));
    }
}
