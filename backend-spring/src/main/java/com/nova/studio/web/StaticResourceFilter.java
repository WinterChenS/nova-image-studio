package com.nova.studio.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Static hosting filter (T1.8) — serves the Next.js static export with SPA
 * fallback + 404 + traversal protection before Spring MVC handler mapping runs.
 *
 * <p>Implemented as a filter rather than a {@code /**} controller so it never
 * shadows the WebSocket endpoint ({@code /api/nova/ws}): the filter only
 * handles plain GETs for non-API, non-actuator paths and lets every other
 * request (including WS upgrades) fall through to Spring MVC.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StaticResourceFilter extends OncePerRequestFilter {

    private final StaticFileResolver resolver;

    public StaticResourceFilter(StaticFileResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean upgrade = "websocket".equalsIgnoreCase(request.getHeader("Upgrade"));
        return !"GET".equalsIgnoreCase(request.getMethod())
                || upgrade
                || path.startsWith("/api/")
                || path.startsWith("/actuator/")
                || path.startsWith("/v3/")           // T3.3: springdoc OpenAPI
                || path.startsWith("/swagger-ui/")   // T3.3: Swagger UI webjars
                || path.startsWith("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        StaticFileResolver.Resolution resolution = resolver.resolve(path);
        if (resolution == null) {
            response.setStatus(404);
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("Not Found");
            return;
        }
        response.setStatus(resolution.status());
        response.setContentType(ImageController.contentTypeFor(resolution.file()));
        response.setContentLengthLong(Files.size(resolution.file()));
        Files.copy(resolution.file(), response.getOutputStream());
    }
}
