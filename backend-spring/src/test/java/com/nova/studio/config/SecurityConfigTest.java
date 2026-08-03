package com.nova.studio.config;

import com.nova.studio.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WIN-16 (ADR-12) — Spring Security 7.1 filter chain behavior over the real
 * MVC surface (servlet stack, web + webflux coexist — ARCH C.3.2.8 ②④):
 * <ul>
 *   <li>anonymous read-only boundary (Q1): public endpoints stay open;</li>
 *   <li>protected endpoints (settings/models CRUD, auth/me, task creation)
 *       reject anonymous/invalid tokens with the Node-style 401 JSON
 *       {@code {"error":"请先登录","code":"UNAUTHORIZED"}} — frontend
 *       {@code {error, code}} parsing stays unchanged;</li>
 *   <li>a valid JWT (issued by the reused {@link JwtService}, jjwt 0.12.6)
 *       reaches the controller via {@code @AuthenticationPrincipal}.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    // ===== anonymous read-only boundary (Q1) =====

    @Test
    void anonymousReadOnlyEndpointsStayOpen() throws Exception {
        mockMvc.perform(get("/api/nova/queue-status"))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCanReadLegacyTask() throws Exception {
        // /api/nova/tasks/* is permitAll; a missing task is a 404, not a 401.
        mockMvc.perform(get("/api/nova/tasks/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousCanHitHealthAndLoginEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized()); // 401 INVALID_CREDENTIALS, not blocked by the chain
    }

    // ===== protected endpoints require auth =====

    @Test
    void anonymousSettingsRequestGetsNodeStyle401() throws Exception {
        mockMvc.perform(get("/api/nova/settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("请先登录"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void anonymousModelsRequestGetsNodeStyle401() throws Exception {
        mockMvc.perform(get("/api/nova/models"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("请先登录"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void anonymousTaskCreationGetsNodeStyle401() throws Exception {
        mockMvc.perform(post("/api/nova/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"text-to-image\",\"prompt\":\"x\",\"parallelCount\":1,\"model\":\"m\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("请先登录"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void anonymousMeGets401Not500() throws Exception {
        // Previously this path NPE'd into a 400; the security chain must 401 it.
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidTokenOnProtectedEndpointGets401() throws Exception {
        mockMvc.perform(get("/api/nova/settings").header("Authorization", "Bearer garbage.token.here"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ===== authenticated access via reused JwtService =====

    @Test
    void validTokenReachesProtectedEndpoints() throws Exception {
        // /api/auth/me refreshes identity from the DB, so the principal must be a
        // real registered user (not a random UUID).
        String token = registerAndLogin("alice_" + UUID.randomUUID().toString().substring(0, 6));
        mockMvc.perform(get("/api/nova/settings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(org.hamcrest.Matchers.startsWith("alice_")));
    }

    @Test
    void csrfDisabledForStatelessJwt() throws Exception {
        // PUT with a valid token must not require a CSRF token (stateless JWT).
        String token = registerAndLogin("bob_" + UUID.randomUUID().toString().substring(0, 6));
        mockMvc.perform(put("/api/nova/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workbench.t2i.prompt\":\"a cat\"}"))
                .andExpect(status().isOk());
    }

    /** Registers a throwaway user via the API and returns a fresh login JWT. */
    private String registerAndLogin(String username) throws Exception {
        String registerBody = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        org.hamcrest.MatcherAssert.assertThat(registerBody, org.hamcrest.Matchers.containsString("\"id\""));
        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(loginBody).get("token").asText();
    }
}
