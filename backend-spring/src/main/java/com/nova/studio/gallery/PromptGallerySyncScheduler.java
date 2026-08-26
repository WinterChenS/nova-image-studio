package com.nova.studio.gallery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * WIN-42 (T15, ADR-40) — 提示广场每日同步调度：默认每日 03:00。cron 经 Spring 属性
 * {@code nova.gallery.sync-cron}（env {@code NOVA_GALLERY_SYNC_CRON} 覆盖）配置；
 * settings 键 {@code gallery.syncCron}（{@link com.nova.studio.settings.SettingsService#KEY_GALLERY_SYNC_CRON}）
 * 为文档约定保留（@Scheduled 表达式需静态 + settings 为 per-user 存储，P2 分布式调度时启用）。
 * 失败不影响其他任务（尽力而为 + 状态落 JSON 可查询）。
 */
@Component
public class PromptGallerySyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PromptGallerySyncScheduler.class);

    private final PromptGallerySyncService syncService;

    public PromptGallerySyncScheduler(PromptGallerySyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${nova.gallery.sync-cron:0 0 3 * * *}")
    public void dailySync() {
        try {
            PromptGallerySyncService.SyncResult result = syncService.scheduledSync();
            log.info("[gallery] 每日同步完成: status={}, total={}", result.status(), result.totalUpserted());
        } catch (Exception e) {
            log.error("[gallery] 每日同步异常", e);
        }
    }
}
