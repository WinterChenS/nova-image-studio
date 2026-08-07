package com.nova.studio.web;

import com.nova.studio.audit.AuditQueryService;
import com.nova.studio.audit.UsageAggService;
import com.nova.studio.audit.UsageRecordRepository;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T24 (WIN-29) — 我的用量 {@code GET /api/nova/usage/me} (A11): 用户自助查询
 * <b>仅本人数据</b> —— the userId filter is always bound to the authenticated
 * principal (never a request parameter), so users cannot see others' rows.
 * Response = detail rows (paged, recent) + summary + daily aggregates
 * (from usage_daily_agg, which survives retention cleanup — A10/T25).
 */
@RestController
@PreAuthorize("hasAuthority('PERM_usage.me')")
@RequestMapping("/api/nova/usage/me")
public class UsageMeController {

    private final AuditQueryService auditQueryService;
    private final UsageAggService aggService;

    public UsageMeController(AuditQueryService auditQueryService, UsageAggService aggService) {
        this.auditQueryService = auditQueryService;
        this.aggService = aggService;
    }

    @GetMapping
    public Map<String, Object> me(@RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(required = false) String reqType,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "50") int size,
                                  @AuthenticationPrincipal AuthUser authUser) {
        AuthUser me = AuthSupport.requireAuth(authUser);
        // A11: userId 恒为当前登录用户 —— 接口不接受 userId 参数
        Instant fromIso = parseInstant(from);
        Instant toIso = parseInstant(to);
        Map<String, Object> query = auditQueryService.query(new UsageRecordRepository.Filters(
                fromIso, toIso, me.id(), null, null, null, reqType, null), page, size);

        // 日聚合（跨保留期存活，A10）：最近 90 天
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate rangeFrom = fromIso != null
                ? fromIso.atZone(ZoneOffset.UTC).toLocalDate()
                : today.minusDays(90);
        LocalDate rangeTo = toIso != null
                ? toIso.atZone(ZoneOffset.UTC).toLocalDate()
                : today;

        Map<String, Object> result = new LinkedHashMap<>(query);
        result.put("daily", aggService.userDaily(me.id(), rangeFrom, rangeTo));
        return result;
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
