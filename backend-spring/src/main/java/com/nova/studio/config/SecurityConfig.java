package com.nova.studio.config;

import com.nova.studio.auth.JwtAuthenticationFilter;
import com.nova.studio.auth.JwtService;
import com.nova.studio.rbac.UserPermissionService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * WIN-16 (ADR-12) + WIN-25 (T13, ADR-30/31) — Spring Security 7.1.0 filter
 * chain.
 *
 * <p><b>M2 门禁收口（ADR-30）</b>：从「opt-in 登录清单 + permitAll 兜底」翻转为
 * <b>白名单 + {@code anyRequest().authenticated()}</b>（D2 默认）：
 * <ul>
 *   <li>公开白名单：登录/注册/忘记密码、健康探针、静态产物（浏览器加载 JS/CSS
 *       无法携带 Bearer）、WS 握手与图片 URL（ADR-31 例外，不可猜测 UUID）;</li>
 *   <li>其余全部登录（401 JSON {@code {error, code}} 收口），前端 authFetch 401
 *       全局引导登录页（Part H.1）；</li>
 *   <li>{@code @EnableMethodSecurity} 开启，管理端点以 {@code @PreAuthorize
 *       ("hasAuthority('PERM_xxx')")} 做权限码鉴权（G.2），普通用户直调 → 403。</li>
 * </ul>
 *
 * <p>WS handshake ({@code /api/nova/ws}, {@code ?token=}) remains permitAll —
 * handled by {@code WsAuthHandshakeInterceptor} as before (ADR-31).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtService jwtService;
    private final UserPermissionService permissionService;

    public SecurityConfig(JwtService jwtService, UserPermissionService permissionService) {
        this.jwtService = jwtService;
        this.permissionService = permissionService;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())                       // stateless JWT, no cookie session
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, 401, "UNAUTHORIZED", "请先登录"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, 403, "FORBIDDEN", "无权访问该资源")))
                .authorizeHttpRequests(auth -> auth
                        // ---- 公开白名单（ADR-30/31, D.2）----
                        .requestMatchers("/api/auth/login", "/api/auth/register",
                                "/api/auth/forgot-password").permitAll()
                        .requestMatchers("/api/nova/health", "/actuator/health").permitAll()
                        // 静态产物（浏览器加载无法携带 Bearer）
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/favicon.png",
                                "/manifest.json", "/sw.js", "/404", "/404.html",
                                "/_next/**", "/static/**", "/assets/**",
                                "/icon-*.png", "/icon-maskable-512.png",
                                "/screenshot-*.png", "/togif.png").permitAll()
                        // ADR-31 例外：WS 握手 + 图片 URL（不可猜测 UUID）
                        .requestMatchers("/api/nova/ws", "/api/nova/images/**").permitAll()
                        // ---- 其余全部登录（401 收口；匿名只读边界 D2 收口）----
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, permissionService),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Cost 12 — identical to the previous hashes (existing rows stay valid). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\",\"code\":\"" + code + "\"}");
    }
}
