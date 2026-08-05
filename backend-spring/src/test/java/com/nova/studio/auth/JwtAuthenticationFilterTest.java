package com.nova.studio.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** WIN-16 (ADR-12) — JwtAuthenticationFilter installs the AuthUser principal. */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "S3GYs4Qf3sLwAgajjSi4/ADjR00ldw9RX2iwL5NgRr6fGU98/24hTLFHu0JySZG7";

    private final JwtService jwt = new JwtService(SECRET);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt);

    @Test
    void validBearerTokenPopulatesSecurityContext() throws Exception {
        UUID id = UUID.randomUUID();
        String token = jwt.issue(id, "alice", "user");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        SecurityContextHolder.clearContext();
        filter.doFilter(request, response, new MockFilterChain());

        SecurityContext context = SecurityContextHolder.getContext();
        assertThat(context.getAuthentication()).isNotNull();
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(AuthUser.class);
        AuthUser user = (AuthUser) context.getAuthentication().getPrincipal();
        assertThat(user.id()).isEqualTo(id);
        assertThat(user.username()).isEqualTo("alice");
        assertThat(context.getAuthentication().getAuthorities())
                .extracting(a -> a.getAuthority())
                .contains("ROLE_user");
    }

    @Test
    void missingTokenLeavesContextEmpty() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void invalidTokenLeavesContextEmpty() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer garbage.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
