package com.nova.studio.audit;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * T7 (WIN-28) — usage collection entry points: the image task worker
 * ({@code TaskQueueService}) and the text proxy controller report request-level
 * outcomes here; the collector normalizes them to one {@code usage_records}
 * row per business request (H5: task 级一条，images=成功图数；proxy 请求 UUID 一条).
 */
@Component
public class UsageCollector {

    private final UsageRecordService usageRecordService;

    public UsageCollector(UsageRecordService usageRecordService) {
        this.usageRecordService = usageRecordService;
    }

    /** Image task result (one row per task; retried 换账号后成功). */
    public record TaskUsage(String taskId, UUID userId, UUID modelId, String protocol, int parallelCount,
                            int successfulImages, boolean retried, boolean failed, UUID accountId, long durationMs) {
    }

    /** Text proxy request result (ref_id = request UUID). */
    public record ProxyUsage(String refId, UUID userId, UUID modelId, UUID accountId, String protocol,
                             boolean retried, boolean failed, Long inputTokens, Long outputTokens, long durationMs) {
    }

    public void recordTaskUsage(TaskUsage t) {
        String status = t.failed() ? "failed" : (t.retried() ? "retried" : "success");
        usageRecordService.record(new UsageRecordService.UsageRecord(
                t.userId(), t.accountId(), t.modelId(), t.protocol(), "image", "task", t.taskId(),
                status, null, null, t.successfulImages(), "CNY", t.durationMs()));
    }

    public void recordProxyUsage(ProxyUsage p) {
        String status = p.failed() ? "failed" : (p.retried() ? "retried" : "success");
        usageRecordService.record(new UsageRecordService.UsageRecord(
                p.userId(), p.accountId(), p.modelId(), p.protocol(), "text", "proxy", p.refId(),
                status, p.inputTokens(), p.outputTokens(), null, "CNY", p.durationMs()));
    }
}
