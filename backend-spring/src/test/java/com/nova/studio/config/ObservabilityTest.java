package com.nova.studio.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T3.2 (WIN-13) — observability acceptance over the real MVC surface:
 * <ul>
 *   <li>{@code GET /actuator/health} is reachable anonymously and returns a
 *       structured JSON status (F-17);</li>
 *   <li>{@code GET /actuator/metrics} is exposed (task metrics surface);</li>
 *   <li>the {@code nova.tasks.*} lifecycle counters are registered (ARCH C.8
 *       任务指标: 排队/处理/完成计数).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservabilityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void healthEndpointReachableAnonymously() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").isString());
    }

    @Test
    void metricsEndpointGatedBehindLogin() throws Exception {
        // ADR-30 门禁收口：白名单仅含 /actuator/health，metrics 需登录（401）
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void taskLifecycleCountersRegistered() {
        assertThat(meterRegistry.find("nova.tasks.queued").counter()).isNotNull();
        assertThat(meterRegistry.find("nova.tasks.processing").counter()).isNotNull();
        assertThat(meterRegistry.find("nova.tasks.completed").counter()).isNotNull();
        assertThat(meterRegistry.find("nova.tasks.failed").counter()).isNotNull();
    }
}
