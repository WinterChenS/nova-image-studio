package com.nova.studio.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * WIN-16 (ADR-12) — replaces the custom {@code AuthFilter}: parses
 * {@code Authorization: Bearer <jwt>} with the reused {@link JwtService}
 * (jjwt 0.12.6, unchanged) and installs the {@link AuthUser} principal into
 * the {@code SecurityContext}. It never blocks — the SecurityFilterChain
 * rules enforce the login boundary and anonymous requests simply stay
 * unauthenticated (Q1).
 *
 * <p>Constructed inline by {@link com.nova.studio.config.SecurityConfig}
 * inside the filter chain (not a servlet-filter bean) so it runs exactly once
 * per request, before the authorization rules are evaluated.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            AuthUser user = jwtService.parse(header.substring(7));
            if (user != null) {
                var auth = new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        filterChain.doFilter(request, response);
    }
}
