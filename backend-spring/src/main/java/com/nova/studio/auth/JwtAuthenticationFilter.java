package com.nova.studio.auth;

import com.nova.studio.rbac.UserPermissionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * WIN-16 (ADR-12) + WIN-25 (T14, ADR-29) — replaces the custom
 * {@code AuthFilter}: parses {@code Authorization: Bearer <jwt>} with the
 * reused {@link JwtService} (jjwt 0.12.6, unchanged) and installs the
 * {@link AuthUser} principal into the {@code SecurityContext}. Since M2 the
 * authorities are <b>ROLE_ + PERM_</b>: the JWT carries only the primary role
 * (quick field), while permission codes are loaded per request from
 * {@link UserPermissionService} (Caffeine ≤1s cache, ADR-29) so RBAC matrix
 * changes take effect without re-login.
 *
 * <p>It never blocks — the SecurityFilterChain rules enforce the login
 * boundary and anonymous requests simply stay unauthenticated (Q1).
 *
 * <p>Constructed inline by {@link com.nova.studio.config.SecurityConfig}
 * inside the filter chain (not a servlet-filter bean) so it runs exactly once
 * per request, before the authorization rules are evaluated.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserPermissionService permissionService;

    public JwtAuthenticationFilter(JwtService jwtService, UserPermissionService permissionService) {
        this.jwtService = jwtService;
        this.permissionService = permissionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            AuthUser user = jwtService.parse(header.substring(7));
            if (user != null) {
                var authorities = new ArrayList<SimpleGrantedAuthority>();
                authorities.add(new SimpleGrantedAuthority("ROLE_" + user.role()));
                try {
                    UserPermissionService.UserPermissions perms = permissionService.load(user.id());
                    for (String code : perms.permissions()) {
                        authorities.add(new SimpleGrantedAuthority("PERM_" + code));
                    }
                } catch (Exception e) {
                    // 权限加载失败不阻断登录（回退仅 ROLE_ 授权；方法级鉴权会 403）
                }
                var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        filterChain.doFilter(request, response);
    }
}
