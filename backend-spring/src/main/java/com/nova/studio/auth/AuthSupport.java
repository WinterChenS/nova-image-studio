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
}
