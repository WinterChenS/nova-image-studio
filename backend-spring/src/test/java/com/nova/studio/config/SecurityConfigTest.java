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
 * WIN-16 (ADR-12) + WIN-25 (T13/T16, ADR-30/31) — Spring Security 7.1 filter
 * chain over the real MVC surface:
 * <ul>
 *   <li><b>门禁收口</b>：白名单外 {@code anyRequest().authenticated()} —— 原匿名只读
 *       端点（tasks/prompts/blacklist/config/queue-status）未登录 → 401（D2 默认，A19）;</li>
 *   <li><b>公开白名单</b>（ADR-31）：登录/注册/忘记密码、健康探针、静态产物、
 *       WS 握手、图片 URL 保持公开;</li>
 *   <li>401/403 JSON 保持 Node 风格 {@code {error, code}}（前端解析不变）;</li>
 *   <li>合法 JWT 经 {@link JwtService} 到达控制器；admin 端点以 @PreAuthorize
 *       权限码鉴权（普通用户 → 403，A13）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    // ===== 公开白名单（ADR-30/31） =====

    @Test
    void healthAndLoginEndpointsStayPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized()); // 401 INVALID_CREDENTIALS, not blocked by the chain
    }

    @Test
    void forgotPasswordIsPublic() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void staticAssetsAndImagesWhitelistStayPublic() throws Exception {
        // 静态产物（浏览器加载无法携带 Bearer，ADR-30）
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/_next/static/chunks/whatever.js")).andExpect(status().isNotFound());
        // ADR-31 例外：图片 URL（不可猜测 UUID，双段 taskId/index）与 WS 握手公开
        mockMvc.perform(get("/api/nova/images/" + UUID.randomUUID() + "/0"))
                .andExpect(status().isNotFound()); // 公开路径可达（404 缺文件，非 401）
    }

    // ===== 原匿名只读边界收口（D2 默认，A19） =====

    @Test
    void anonymousReadOnlyEndpointsNowReject401() throws Exception {
        mockMvc.perform(get("/api/nova/queue-status"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/nova/prompts"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/nova/blacklist"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/nova/config"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousLegacyTaskReadNow401() throws Exception {
        // /api/nova/tasks/* 由匿名只读改为登录后访问（D2）
        mockMvc.perform(get("/api/nova/tasks/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
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

    @Test
    void anonymousAdminEndpointGets401() throws Exception {
        mockMvc.perform(get("/api/nova/admin/accounts"))
                .andExpect(status().isUnauthorized());
    }

    // ===== authenticated access via reused JwtService =====

    @Test
    void validTokenReachesProtectedEndpoints() throws Exception {
        String token = registerAndLogin("alice_" + UUID.randomUUID().toString().substring(0, 6));
        mockMvc.perform(get("/api/nova/settings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(org.hamcrest.Matchers.startsWith("alice_")));
    }

    @Test
    void csrfDisabledForStatelessJwt() throws Exception {
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
