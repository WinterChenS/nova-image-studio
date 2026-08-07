package com.nova.studio.audit;

import com.nova.studio.accountpool.PricingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T7 (WIN-28) — usage record service (ADR-26): bounded async write queue
 * (default 10 000, drop + warn when full — never blocks the generation path),
 * idempotent {@code INSERT ... ON CONFLICT DO NOTHING} (R2/A20), and the cost
 * snapshot at write time (B3/A8: 单次价 + tokens × 每 token 单价，改价不影响历史).
 */
@Service
public class UsageRecordService {

    private static final Logger log = LoggerFactory.getLogger(UsageRecordService.class);

    /** Request-level usage row (cost computed at write time, not here). */
    public record UsageRecord(UUID userId, UUID accountId, UUID modelId, String protocol,
                              String reqType, String refType, String refId, String status,
                              Long inputTokens, Long outputTokens, Integer images,
                              String currency, Long durationMs) {
    }

    private final UsageRecordMapper mapper;
    private final PricingService pricingService;
    private final LinkedBlockingQueue<Runnable> queue;
    private final Thread consumer;
    private final AtomicBoolean consumerBusy = new AtomicBoolean(false);

    public UsageRecordService(UsageRecordMapper mapper,
                              PricingService pricingService,
                              @Value("${NOVA_AUDIT_QUEUE_CAPACITY:10000}") int queueCapacity) {
        this.mapper = mapper;
        this.pricingService = pricingService;
        this.queue = new LinkedBlockingQueue<>(Math.max(1, queueCapacity));
        this.consumer = new Thread(this::drainLoop, "usage-record-writer");
        this.consumer.setDaemon(true);
        this.consumer.start();
    }

    /** Async write (bounded; drops with a warning when full). */
    public void record(UsageRecord rec) {
        if (!queue.offer(() -> recordSync(rec))) {
            log.warn("[usage] 审计写入队列已满，丢弃记录 ref={}/{}", rec.refType(), rec.refId());
        }
    }

    /** Synchronous idempotent insert — cost snapshotted here (B3). */
    public int recordSync(UsageRecord rec) {
        String currency = rec.currency() == null || rec.currency().isBlank() ? "CNY" : rec.currency();
        BigDecimal cost = pricingService.computeCost(rec.modelId(), rec.inputTokens(), rec.outputTokens(), currency);
        return mapper.insertIgnore(rec.userId(), rec.accountId(), rec.modelId(), rec.protocol(), rec.reqType(),
                rec.refType(), rec.refId(), rec.status(), rec.inputTokens(), rec.outputTokens(), rec.images(),
                cost, currency, rec.durationMs(), Instant.now());
    }

    /** Drains the pending queue and waits for in-flight consumer work (tests / shutdown). */
    public void flush() {
        while (true) {
            Runnable task = queue.poll();
            if (task != null) {
                try {
                    task.run();
                } catch (Exception e) {
                    log.warn("[usage] 异步写入失败: {}", e.getMessage());
                }
                continue;
            }
            if (!consumerBusy.get()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void drainLoop() {
        while (true) {
            try {
                Runnable task = queue.take();
                consumerBusy.set(true);
                try {
                    task.run();
                } catch (Exception e) {
                    log.warn("[usage] 异步写入失败: {}", e.getMessage());
                } finally {
                    consumerBusy.set(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
