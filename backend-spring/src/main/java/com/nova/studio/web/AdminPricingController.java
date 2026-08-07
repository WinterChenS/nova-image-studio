package com.nova.studio.web;

import com.nova.studio.accountpool.PricingService;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T9 (WIN-28) — price table API {@code /api/nova/admin/pricing} (B3 dual rate:
 * per_request_price + price_per_token; UNIQUE(model_id, currency)). Admin-only
 * (M1: requireAdmin; M2 T14 → PERM_pricing.manage).
 */
@RestController
@PreAuthorize("hasAuthority('PERM_pricing.manage')")
@RequestMapping("/api/nova/admin/pricing")
public class AdminPricingController {

    private final PricingService pricingService;

    public AdminPricingController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return pricingService.list();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> upsert(@RequestBody JsonNode body,
                                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthUser admin = AuthSupport.requireAdmin(authUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(pricingService.upsert(admin.id(), body));
    }

    @DeleteMapping("/{modelId}")
    public Map<String, Object> delete(@PathVariable UUID modelId,
                                      @RequestParam(defaultValue = "CNY") String currency,
                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthUser admin = AuthSupport.requireAdmin(authUser);
        pricingService.delete(admin.id(), modelId, currency);
        return Map.of("ok", true);
    }
}
