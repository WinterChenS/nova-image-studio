package com.nova.studio.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T3.3 (WIN-13) — springdoc OpenAPI (F-18): the generated spec
 * ({@code /v3/api-docs}) is reachable anonymously and documents the settings /
 * tasks surface; the Swagger UI is served at {@code /swagger-ui/index.html}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsReachableAndCoverSettingsTasks() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/nova/settings']").exists())
                .andExpect(jsonPath("$.paths['/api/nova/tasks']").exists())
                .andExpect(jsonPath("$.paths['/api/nova/admin/prompts']").exists());
    }

    @Test
    void swaggerUiReachable() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
