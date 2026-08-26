package com.nova.studio.gallery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * WIN-42 (T15, ADR-40) — 提示广场同步服务：每日定时（可配 cron）+ 手动刷新
 * （限频 gallery.syncManualCooldownMinutes，默认 10min）。流程：逐源 fetch →
 * PromptParserRegistry 解析 → 幂等 upsert（id=source-uniqueKey）；单源失败
 * try/catch 隔离不影响其他源；raw_snapshot 保存源原始数据。
 *
 * <p>同步状态落 JSON 文件（gallery-sync-status.json，与 prompts.json/blacklist.json
 * 既有文件模式一致；settings 表为 per-user 且 gallery 为全局数据，故不落 settings
 * 表——见交付说明）。管理端经 {@code GET /api/nova/admin/prompt-gallery/sync/status} 查询。
 */
@Service
public class PromptGallerySyncService {

    private static final Logger log = LoggerFactory.getLogger(PromptGallerySyncService.class);

    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";

    /** 手动刷新限频默认（分钟，A5：settings gallery.syncManualCooldownMinutes 覆盖）。 */
    public static final int DEFAULT_MANUAL_COOLDOWN_MINUTES = 10;

    /** 源列表（Java 移植自前端 PROMPT_DATA_SOURCES，ADR-40）。 */
    static final List<PromptDataSource> DATA_SOURCES = List.of(
            new PromptDataSource("nanobanana",
                    "https://proxy.ccode.vip/https/raw.githubusercontent.com/unknowlei/nanobanana-website/refs/heads/main/public/data.json",
                    "https://github.com/unknowlei/nanobanana-website",
                    "nanobanana", null, null, null),
            new PromptDataSource("gpt-image-2-prompts",
                    "https://proxy.ccode.vip/https/raw.githubusercontent.com/EvoLinkAI/awesome-gpt-image-2-API-and-Prompts/main/data/ingested_tweets.json",
                    "https://github.com/EvoLinkAI/awesome-gpt-image-2-API-and-Prompts",
                    "gpt-image-2",
                    "https://proxy.ccode.vip/https/raw.githubusercontent.com/EvoLinkAI/awesome-gpt-image-2-API-and-Prompts/main",
                    List.of("README.md", "cases/ad-creative.md", "cases/character.md",
                            "cases/comparison.md", "cases/ecommerce.md", "cases/portrait.md",
                            "cases/poster.md", "cases/ui.md"),
                    null),
            new PromptDataSource("awesome-gpt-image",
                    "https://proxy.ccode.vip/https/raw.githubusercontent.com/ZeroLu/awesome-gpt-image/main/README.zh-CN.md",
                    "https://github.com/ZeroLu/awesome-gpt-image",
                    "markdown-awesome",
                    "https://proxy.ccode.vip/https/raw.githubusercontent.com/ZeroLu/awesome-gpt-image/main",
                    null, null),
            new PromptDataSource("awesome-gpt4o-image-prompts",
                    "https://proxy.ccode.vip/https/raw.githubusercontent.com/ImgEdify/Awesome-GPT4o-Image-Prompts/main/README.zh-CN.md",
                    "https://github.com/ImgEdify/Awesome-GPT4o-Image-Prompts",
                    "markdown-gpt4o",
                    "https://proxy.ccode.vip/https/raw.githubusercontent.com/ImgEdify/Awesome-GPT4o-Image-Prompts/main",
                    null, null),
            new PromptDataSource("youmind-gpt-image-2",
                    "https://proxy.ccode.vip/https/raw.githubusercontent.com/YouMind-OpenLab/awesome-gpt-image-2/main/README_zh.md",
                    "https://github.com/YouMind-OpenLab/awesome-gpt-image-2",
                    "markdown-youmind",
                    "https://proxy.ccode.vip/https/raw.githubusercontent.com/YouMind-OpenLab/awesome-gpt-image-2/main",
                    null, "gpt-image-2"),
            new PromptDataSource("youmind-nano-banana-pro",
                    "https://proxy.ccode.vip/https/raw.githubusercontent.com/YouMind-OpenLab/awesome-nano-banana-pro-prompts/main/README_zh.md",
                    "https://github.com/YouMind-OpenLab/awesome-nano-banana-pro-prompts",
                    "markdown-youmind",
                    "https://proxy.ccode.vip/https/raw.githubusercontent.com/YouMind-OpenLab/awesome-nano-banana-pro-prompts/main",
                    null, "nano-banana-pro"),
            new PromptDataSource("davidwu-gpt-image2-prompts",
                    "https://proxy.ccode.vip/https/raw.githubusercontent.com/davidwuw0811-boop/awesome-gpt-image2-prompts/main/prompts.json",
                    "https://github.com/davidwuw0811-boop/awesome-gpt-image2-prompts",
                    "davidwu-json",
                    "https://proxy.ccode.vip/https/raw.githubusercontent.com/davidwuw0811-boop/awesome-gpt-image2-prompts/main",
                    null, null)
    );

    /** 单个源同步结果。 */
    public record SourceResult(String source, String status, int upserted, String error) {
    }

    /** 一次同步的汇总。 */
    public record SyncResult(String status, Instant startedAt, Instant finishedAt,
                             int totalUpserted, List<SourceResult> sources, String error) {
    }

    /** 单源运行时的易变状态（供手动同步检查限频前写入）。 */
    private static final class MutableSyncStatus {
        String status = STATUS_IDLE;
        Instant startedAt;
        Instant finishedAt;
        int totalUpserted;
        final List<SourceResult> sources = new ArrayList<>();
        String error;
    }

    private final PromptGalleryItemRepository repository;
    private final PromptParserRegistry registry;
    private final PromptSourceFetcher fetcher;
    private final ObjectMapper objectMapper;
    private final Path statusPath;

    private volatile Instant lastManualSyncAt;
    private final Object lock = new Object();

    @org.springframework.beans.factory.annotation.Autowired
    public PromptGallerySyncService(PromptGalleryItemRepository repository,
                                    PromptParserRegistry registry,
                                    @Value("${nova.storage.gallery-status-path:../backend/gallery-sync-status.json}") String statusPath,
                                    ObjectMapper objectMapper) {
        this.repository = repository;
        this.registry = registry;
        this.fetcher = new HttpPromptSourceFetcher();
        this.objectMapper = objectMapper;
        this.statusPath = Path.of(statusPath);
        this.lastManualSyncAt = readLastManualSyncAt();
    }

    /** 测试构造（注入桩 fetcher）。 */
    PromptGallerySyncService(PromptGalleryItemRepository repository,
                             PromptParserRegistry registry,
                             PromptSourceFetcher fetcher,
                             String statusPath,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.registry = registry;
        this.fetcher = fetcher;
        this.objectMapper = objectMapper;
        this.statusPath = Path.of(statusPath);
        this.lastManualSyncAt = readLastManualSyncAt();
    }

    // ===== public API =====

    /** 数据源列表（管理端展示/前端来源筛选用）。 */
    public List<PromptDataSource> dataSources() {
        return DATA_SOURCES;
    }

    /**
     * 手动刷新（限频）：距上次手动刷新 < cooldown 分钟 → 409（A5）。
     * 返回 409 需由调用方转成 HttpErrorException。
     */
    public synchronized SyncResult manualSync(int cooldownMinutes) {
        Instant now = Instant.now();
        int cooldown = cooldownMinutes > 0 ? cooldownMinutes : DEFAULT_MANUAL_COOLDOWN_MINUTES;
        if (lastManualSyncAt != null) {
            long elapsedMin = java.time.Duration.between(lastManualSyncAt, now).toMinutes();
            if (elapsedMin < cooldown) {
                throw new com.nova.studio.infra.HttpErrorException(409, "SYNC_COOLDOWN",
                        "手动刷新过于频繁，请 " + (cooldown - elapsedMin) + " 分钟后重试",
                        (int) ((cooldown - elapsedMin) * 60));
            }
        }
        lastManualSyncAt = now;
        SyncResult result = runSync();
        persistLastManualSyncAt(lastManualSyncAt);
        return result;
    }

    /** 定时同步（每日 cron，无限频）。 */
    public synchronized SyncResult scheduledSync() {
        return runSync();
    }

    /** 最近一次同步状态（管理端查询，AC-12）。 */
    public ObjectNode status() {
        return readStatusFile();
    }

    // ===== internals =====

    private SyncResult runSync() {
        MutableSyncStatus state = new MutableSyncStatus();
        state.status = STATUS_RUNNING;
        state.startedAt = Instant.now();
        log.info("[gallery] 提示广场同步开始, sources={}", DATA_SOURCES.size());

        for (PromptDataSource source : DATA_SOURCES) {
            SourceResult sourceResult = syncSource(source);
            state.sources.add(sourceResult);
            state.totalUpserted += sourceResult.upserted();
        }

        state.finishedAt = Instant.now();
        state.status = state.sources.stream().anyMatch(s -> "failed".equals(s.status()))
                ? (state.totalUpserted > 0 ? STATUS_SUCCEEDED : STATUS_FAILED)
                : STATUS_SUCCEEDED;
        writeStatusFile(state);
        log.info("[gallery] 提示广场同步完成: status={}, total={}, sources={}",
                state.status, state.totalUpserted, state.sources.size());
        return new SyncResult(state.status, state.startedAt, state.finishedAt,
                state.totalUpserted, List.copyOf(state.sources), state.error);
    }

    /** 单源同步：fetch → parse → upsert；失败 try/catch 隔离（ADR-40 容错）。 */
    private SourceResult syncSource(PromptDataSource source) {
        try {
            PromptParser parser = registry.get(source.type());
            if (parser == null) {
                log.warn("[gallery] 无匹配解析器: type={}", source.type());
                return new SourceResult(source.name(), "failed", 0, "无匹配解析器: " + source.type());
            }
            String raw = fetcher.fetch(source.url());
            List<ParsedPrompt> prompts = parser.parse(source, raw, fetcher);
            int upserted = 0;
            for (ParsedPrompt prompt : prompts) {
                upsert(prompt);
                upserted++;
            }
            log.info("[gallery] 源同步成功: source={}, upserted={}", source.name(), upserted);
            return new SourceResult(source.name(), "succeeded", upserted, null);
        } catch (Exception e) {
            log.warn("[gallery] 源同步失败（单源容错，不影响其他源）: source={}: {}", source.name(), e.getMessage());
            return new SourceResult(source.name(), "failed", 0,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void upsert(ParsedPrompt prompt) {
        repository.upsert(prompt.id(), prompt.source(), prompt.sourceUrl(), prompt.title(),
                prompt.content(), toJson(prompt.images()), toJson(prompt.tags()),
                prompt.category(), prompt.contributor(), prompt.notes(), prompt.rawSnapshot(),
                Instant.now());
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ===== status file (JSON) =====

    private ObjectNode readStatusFile() {
        try {
            if (Files.exists(statusPath)) {
                String raw = Files.readString(statusPath, StandardCharsets.UTF_8);
                JsonNode node = objectMapper.readTree(raw);
                if (node != null && node.isObject()) {
                    return (ObjectNode) node;
                }
            }
        } catch (Exception e) {
            log.warn("[gallery] 同步状态文件读取失败: {}", e.getMessage());
        }
        ObjectNode empty = objectMapper.createObjectNode();
        empty.put("status", STATUS_IDLE);
        return empty;
    }

    private void writeStatusFile(MutableSyncStatus state) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("status", state.status);
            if (state.startedAt != null) {
                node.put("startedAt", state.startedAt.toString());
            }
            if (state.finishedAt != null) {
                node.put("finishedAt", state.finishedAt.toString());
            }
            node.put("totalUpserted", state.totalUpserted);
            ArrayNode sources = objectMapper.createArrayNode();
            for (SourceResult s : state.sources) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("source", s.source());
                item.put("status", s.status());
                item.put("upserted", s.upserted());
                if (s.error() != null) {
                    item.put("error", s.error());
                }
                sources.add(item);
            }
            node.set("sources", sources);
            Files.writeString(statusPath, node.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[gallery] 同步状态文件写入失败: {}", e.getMessage());
        }
    }

    private Instant readLastManualSyncAt() {
        ObjectNode status = readStatusFile();
        if (status.hasNonNull("lastManualSyncAt")) {
            try {
                return Instant.parse(status.get("lastManualSyncAt").asText());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private void persistLastManualSyncAt(Instant at) {
        try {
            ObjectNode node = readStatusFile();
            node.put("lastManualSyncAt", at.toString());
            Files.writeString(statusPath, node.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[gallery] 手动同步时间持久化失败: {}", e.getMessage());
        }
    }
}
