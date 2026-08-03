package com.nova.studio.config;

import com.nova.studio.auth.JwtAuthenticationFilter;
import com.nova.studio.auth.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
 * WIN-16 (ADR-12) — full Spring Security 7.1.0 filter chain replacing the
 * custom {@code AuthFilter} (ARCH C.3.2.6).
 *
 * <p>Option 1 rule boundary (zero drift vs. the in-flight behavior): the chain
 * declares only the <b>must-login</b> rules (task creation, settings/models
 * CRUD, auth/me); everything else stays {@code permitAll()} so the anonymous
 * read-only boundary (Q1) and legacy endpoints keep working unchanged.
 * Controllers still enforce login via {@code @AuthenticationPrincipal} +
 * {@code AuthSupport.requireAuth} where the ARCH keeps that semantic.
 *
 * <p>401/403 JSON follows the Node-style {@code {error, code}} envelope
 * (same shape as {@code HttpErrorException}) so frontend error parsing stays
 * untouched. WS handshake ({@code /api/nova/ws}, {@code ?token=}) remains
 * permitAll — handled by {@code WsAuthHandshakeInterceptor} as before.
 *
 * <p>Security 7.x notes: {@code and()} DSL is gone (lambda style only);
 * {@code requestMatchers} use {@code PathPatternRequestMatcher}; the JWT
 * filter is registered <em>inside</em> the chain (not as a servlet filter
 * bean) so it runs exactly once, before authorization evaluation.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtService jwtService;

    public SecurityConfig(JwtService jwtService) {
        this.jwtService = jwtService;
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
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/nova/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/nova/tasks").authenticated()   // Q1: task creation requires login
                        .requestMatchers("/api/auth/me", "/api/nova/settings/**", "/api/nova/models/**").authenticated()
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico",
                                "/api/nova/ws", "/api/nova/images/**", "/api/nova/queue-status",
                                "/api/nova/prompts", "/api/nova/blacklist", "/api/nova/config",
                                "/api/nova/proxy/**", "/api/nova/tasks/*").permitAll()          // anonymous read-only (Q1)
                        .anyRequest().permitAll())                  // option 1 fallback: boundaries enforced in controllers
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
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
