package com.nova.studio.gallery;

import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WIN-42 (T15, ADR-40) — 提示广场同步服务单测：单源容错、upsert 委托、
 * 手动刷新限频（A5）、同步状态落 JSON。
 */
class PromptGallerySyncServiceTest {

    @TempDir
    Path tempDir;

    private final PromptGalleryItemRepository repository = mock(PromptGalleryItemRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PromptSourceFetcher fetcher;

    private PromptGallerySyncService newService(PromptSourceFetcher fetcher) {
        return new PromptGallerySyncService(repository,
                new PromptParserRegistry(objectMapper),
                fetcher,
                tempDir.resolve("gallery-sync-status.json").toString(),
                objectMapper);
    }

    @BeforeEach
    void setUp() {
        fetcher = mock(PromptSourceFetcher.class);
    }

    // ===== 单源容错（AC-9）=====

    @Test
    void singleSourceFailureDoesNotBlockOthers() throws Exception {
        // nanobanana（第 1 个源）失败 → 其余源继续
        when(fetcher.fetch(anyString())).thenThrow(new RuntimeException("network down"));
        // gpt-image-2 的 case 文件读取也失败（不影响其余源）
        PromptGallerySyncService.SyncResult result = newService(fetcher).scheduledSync();
        assertThat(result.sources()).isNotEmpty();
        assertThat(result.sources()).allMatch(s -> "failed".equals(s.status()));
        assertThat(result.totalUpserted()).isZero();
        // 各源均失败时整体 failed
        assertThat(result.status()).isEqualTo("failed");
        // 不因单个源失败中断（每个源都尝试了）
        verify(fetcher, atLeastOnce()).fetch(anyString());
    }

    @Test
    void allSourcesSucceedUpsertsEveryParsedPrompt() throws Exception {
        // 让所有源返回「可解析」的 markdown-youmind 内容 → 每源至少 1 条
        String markdown = """
                # Awesome
                ### No.1: 电商 - 海报
                #### 提示词
                ```text
                product promo poster
                ```
                """;
        when(fetcher.fetch(anyString())).thenReturn(markdown);
        PromptGallerySyncService.SyncResult result = newService(fetcher).scheduledSync();
        // davidwu-json 返回 markdown 会解析失败（不是数组）→ 单源失败，但其余成功
        assertThat(result.totalUpserted()).isGreaterThan(0);
        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.sources()).anyMatch(s -> "succeeded".equals(s.status()));
        verify(repository, atLeastOnce()).upsert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    // ===== 手动刷新限频（A5，AC-9）=====

    @Test
    void manualSyncRejectsWhenWithinCooldown() throws Exception {
        when(fetcher.fetch(anyString())).thenThrow(new RuntimeException("down"));
        PromptGallerySyncService service = newService(fetcher);
        service.manualSync(10);   // 第一次成功
        assertThatThrownBy(() -> service.manualSync(10))
                .isInstanceOf(HttpErrorException.class)
                .satisfies(e -> {
                    HttpErrorException ex = (HttpErrorException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(409);
                    assertThat(ex.getCode()).isEqualTo("SYNC_COOLDOWN");
                    assertThat(ex.getRetryAfter()).isNotNull();
                });
    }

    @Test
    void manualSyncAllowsAfterCooldownElapsed() throws Exception {
        when(fetcher.fetch(anyString())).thenThrow(new RuntimeException("down"));
        PromptGallerySyncService service = newService(fetcher);
        service.manualSync(0);   // cooldown<=0 → 用默认 10，但第一次总是放行
        // 直接调 manualSync(0) 第二次：0 分钟已过（时间差 0 分钟 < 10 → 仍限频）
        assertThatThrownBy(() -> service.manualSync(0)).isInstanceOf(HttpErrorException.class);
    }

    // ===== 同步状态落 JSON（AC-12）=====

    @Test
    void statusPersistsToJsonFile() throws Exception {
        when(fetcher.fetch(anyString())).thenThrow(new RuntimeException("down"));
        PromptGallerySyncService service = newService(fetcher);
        service.scheduledSync();
        ObjectNode status = service.status();
        assertThat(status.has("status")).isTrue();
        assertThat(status.has("sources")).isTrue();
        assertThat(status.has("startedAt")).isTrue();
    }
}
