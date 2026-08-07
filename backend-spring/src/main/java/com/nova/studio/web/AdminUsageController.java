package com.nova.studio.web;

import com.nova.studio.audit.AuditQueryService;
import com.nova.studio.audit.UsageRecordRepository;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * T10 (WIN-28) — audit & cost API {@code /api/nova/admin/usage} (A9):
 * filtered query (from/to/user/model/account/protocol/reqType/status +
 * pagination + summary cards) and CSV export (≤100k rows, audit.export).
 * Admin-only (M1: requireAdmin; M2 T14 → PERM_audit.view / PERM_audit.export).
 */
@RestController
@PreAuthorize("hasAuthority('PERM_audit.view')")
@RequestMapping("/api/nova/admin/usage")
public class AdminUsageController {

    private final AuditQueryService auditQueryService;

    public AdminUsageController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    public Map<String, Object> query(@RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) String userId,
                                     @RequestParam(required = false) String modelId,
                                     @RequestParam(required = false) String accountId,
                                     @RequestParam(required = false) String protocol,
                                     @RequestParam(required = false) String reqType,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "50") int size,
                                     @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return auditQueryService.query(new UsageRecordRepository.Filters(
                parseInstant(from), parseInstant(to), parseUuid(userId), parseUuid(modelId),
                parseUuid(accountId), protocol, reqType, status), page, size);
    }

    @PreAuthorize("hasAuthority('PERM_audit.export')")
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String userId,
                                         @RequestParam(required = false) String modelId,
                                         @RequestParam(required = false) String accountId,
                                         @RequestParam(required = false) String protocol,
                                         @RequestParam(required = false) String reqType,
                                         @RequestParam(required = false) String status,
                                         @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        String csv = auditQueryService.exportCsv(new UsageRecordRepository.Filters(
                parseInstant(from), parseInstant(to), parseUuid(userId), parseUuid(modelId),
                parseUuid(accountId), protocol, reqType, status));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=usage-export.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
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

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
