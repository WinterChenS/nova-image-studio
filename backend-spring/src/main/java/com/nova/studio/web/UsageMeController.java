package com.nova.studio.web;

import com.nova.studio.audit.UsageMeService;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * T24 (WIN-29) — 我的用量 {@code GET /api/nova/usage/me} (A11): user
 * self-service query of their own usage summary + paged detail + daily trend.
 * <b>Only the authenticated user's own data is ever returned</b> — the service
 * is bound to {@code authUser.id()} and exposes no target-user parameter.
 * Permission {@code PERM_usage.me} (V6: default for the user role).
 */
@RestController
@PreAuthorize("hasAuthority('PERM_usage.me')")
@RequestMapping("/api/nova/usage/me")
public class UsageMeController {

    private final UsageMeService usageMeService;

    public UsageMeController(UsageMeService usageMeService) {
        this.usageMeService = usageMeService;
    }

    @GetMapping
    public Map<String, Object> myUsage(@RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return usageMeService.myUsage(authUser.id(), parseInstant(from), parseInstant(to), page, size);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
