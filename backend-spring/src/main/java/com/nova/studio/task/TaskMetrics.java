package com.nova.studio.task;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * T3.2 (WIN-13) — task lifecycle metrics exposed through Micrometer/Actuator
 * (ARCH C.8 可观测性「任务指标（排队/处理/完成计数）」). Wired at the queue
 * state-machine transition points in {@link TaskService} (queued) and
 * {@link TaskQueueService} (processing/completed/failed). Surfaced at
 * {@code /actuator/metrics/nova.tasks.*}.
 */
@Component
public class TaskMetrics {

    private final Counter queued;
    private final Counter processing;
    private final Counter completed;
    private final Counter failed;

    public TaskMetrics(MeterRegistry registry) {
        this.queued = Counter.builder("nova.tasks.queued")
                .description("任务进入队列计数 (排队中)")
                .register(registry);
        this.processing = Counter.builder("nova.tasks.processing")
                .description("任务进入 processing 计数")
                .register(registry);
        this.completed = Counter.builder("nova.tasks.completed")
                .description("任务完成计数")
                .register(registry);
        this.failed = Counter.builder("nova.tasks.failed")
                .description("任务失败计数")
                .register(registry);
    }

    public void taskQueued() {
        queued.increment();
    }

    public void taskProcessing() {
        processing.increment();
    }

    public void taskCompleted() {
        completed.increment();
    }

    public void taskFailed() {
        failed.increment();
    }
}
