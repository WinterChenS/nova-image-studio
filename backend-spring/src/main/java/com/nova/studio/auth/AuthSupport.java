package com.nova.studio.auth;

import com.nova.studio.infra.HttpErrorException;

/**
 * WIN-16 (ADR-12) — shared "must be logged in" guard for controllers that
 * receive the principal via {@code @AuthenticationPrincipal AuthUser}. Keeps
 * the old {@code AuthFilter.require(request)} 401 contract ({@code {error,
 * code}} Node-style envelope) for the paths where the ARCH keeps controller
 * enforcement semantics.
 */
public final class AuthSupport {

    private AuthSupport() {
    }

    public static AuthUser requireAuth(AuthUser user) {
        if (user == null) {
            throw new HttpErrorException(401, "UNAUTHORIZED", "请先登录");
        }
        return user;
    }

    /** T3.1 (WIN-13) — admin-gated management API: 403 for non-admin, 401 anonymous. */
    public static AuthUser requireAdmin(AuthUser user) {
        AuthUser auth = requireAuth(user);
        if (!auth.isAdmin()) {
            throw new HttpErrorException(403, "FORBIDDEN", "无权访问该资源");
        }
        return auth;
    }
}
