package com.nova.studio.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Optional bearer-token authenticator (T2.2). Parses
 * {@code Authorization: Bearer <jwt>} and attaches {@link AuthUser} as the
 * {@code authUser} request attribute when valid. It never blocks — the
 * protected services/controllers enforce the login boundary (task creation,
 * settings/models CRUD) and the anonymous read-only boundary per Q1.
 *
 * <p>WebSocket handshakes (browser cannot set headers) pass the token via
 * {@code ?token=} and are handled by {@code WsAuthHandshakeInterceptor}.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String AUTH_USER_ATTRIBUTE = "authUser";

    private final JwtService jwtService;

    public AuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            AuthUser user = jwtService.parse(header.substring(7));
            if (user != null) {
                request.setAttribute(AUTH_USER_ATTRIBUTE, user);
            }
        }
        filterChain.doFilter(request, response);
    }

    /** Read the authenticated user from a request; null when anonymous. */
    public static AuthUser current(HttpServletRequest request) {
        Object value = request.getAttribute(AUTH_USER_ATTRIBUTE);
        return value instanceof AuthUser user ? user : null;
    }

    /** Require an authenticated user or throw 401 (Node-style JSON body). */
    public static AuthUser require(HttpServletRequest request) {
        AuthUser user = current(request);
        if (user == null) {
            throw new com.nova.studio.infra.HttpErrorException(401, "UNAUTHORIZED", "请先登录");
        }
        return user;
    }
}
