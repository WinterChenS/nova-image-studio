package com.nova.studio.web;

import com.nova.studio.accountpool.AccountService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T3 (WIN-28) — account pool management API {@code /api/nova/admin/accounts}
 * (A1/A4): CRUD + pause/resume + broken recovery + connectivity test.
 * Admin-only (M1: {@link AuthSupport#requireAdmin}; M2 T14 → PERM_account.manage).
 */
@RestController
@PreAuthorize("hasAuthority('PERM_account.manage')")
@RequestMapping("/api/nova/admin/accounts")
public class AdminAccountController {

    private final AccountService accountService;

    public AdminAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return accountService.list();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody JsonNode body,
                                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthUser admin = AuthSupport.requireAdmin(authUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.create(admin.id(), body));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody JsonNode body,
                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthUser admin = AuthSupport.requireAdmin(authUser);
        return accountService.update(admin.id(), id, body);
    }

    /** Soft delete (status=deleted, ADR-28). */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id, @AuthenticationPrincipal AuthUser authUser) {
        AuthUser admin = AuthSupport.requireAdmin(authUser);
        accountService.delete(admin.id(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/pause")
    public Map<String, Object> pause(@PathVariable UUID id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return accountService.pause(id);
    }

    @PostMapping("/{id}/resume")
    public Map<String, Object> resume(@PathVariable UUID id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return accountService.resume(id);
    }

    /** Admin recovery from broken (A4/A22) — never automatic. */
    @PostMapping("/{id}/recover")
    public Map<String, Object> recover(@PathVariable UUID id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return accountService.recover(id);
    }

    /** Lightweight connectivity test (success clears failure state). */
    @PreAuthorize("hasAuthority('PERM_account.test')")
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable UUID id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return accountService.testConnection(id);
    }
}
