package com.nova.studio.task;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3.2 (WIN-13) — task lifecycle metrics (ARCH C.8 可观测性: 排队/处理/完成
 * 计数). A plain {@link SimpleMeterRegistry} asserts the counters exist and
 * increment exactly once per transition.
 */
class TaskMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void registersAllFourCounters() {
        TaskMetrics metrics = new TaskMetrics(registry);
        assertThat(registry.find("nova.tasks.queued").counter()).isNotNull();
        assertThat(registry.find("nova.tasks.processing").counter()).isNotNull();
        assertThat(registry.find("nova.tasks.completed").counter()).isNotNull();
        assertThat(registry.find("nova.tasks.failed").counter()).isNotNull();
    }

    @Test
    void countersIncrementOncePerTransition() {
        TaskMetrics metrics = new TaskMetrics(registry);
        metrics.taskQueued();
        metrics.taskQueued();
        metrics.taskProcessing();
        metrics.taskCompleted();
        metrics.taskFailed();
        assertThat(registry.find("nova.tasks.queued").counter().count()).isEqualTo(2);
        assertThat(registry.find("nova.tasks.processing").counter().count()).isEqualTo(1);
        assertThat(registry.find("nova.tasks.completed").counter().count()).isEqualTo(1);
        assertThat(registry.find("nova.tasks.failed").counter().count()).isEqualTo(1);
    }
}
