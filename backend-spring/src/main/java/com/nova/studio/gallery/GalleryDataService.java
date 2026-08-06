package com.nova.studio.gallery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * T3.1 — prompts/blacklist DB-ization (F-14, ARCH H5):
 *
 * <ul>
 *   <li><b>Seed import</b> — on first startup ({@link #seedIfEmpty()}, invoked
 *       from {@code @PostConstruct}) an empty {@code prompts}/{@code blacklist}
 *       table is seeded from the legacy files ({@code prompts.json} /
 *       {@code blacklist.json}). Seeding only happens when the table is empty,
 *       so admin edits survive restarts.</li>
 *   <li><b>DB-first reads with file fallback</b> — the public endpoints read
 *       from the DB; if the DB read fails (migration-period safety net) the
 *       service falls back to the legacy file so the frontend keeps working.</li>
 *   <li><b>Admin CRUD</b> — prompts and blacklist keywords are managed through
 *       {@code GalleryAdminController} (admin role enforced there).</li>
 * </ul>
 */
@Service
public class GalleryDataService {

    private static final Logger log = LoggerFactory.getLogger(GalleryDataService.class);

    /** Public row shape for {@code GET /api/nova/prompts} (checklist #12 parity). */
    public record PromptRow(UUID id, String title, String content, int type, boolean enabled, int sortOrder) {
    }

    /** Admin row shape for blacklist keywords. */
    public record KeywordRow(UUID id, String keyword) {
    }

    private final PromptMapper promptMapper;
    private final BlacklistMapper blacklistMapper;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final Path promptsPath;
    private final Path blacklistPath;

    public GalleryDataService(PromptMapper promptMapper, BlacklistMapper blacklistMapper,
                              tools.jackson.databind.ObjectMapper objectMapper,
                              @Value("${nova.storage.prompts-path:../backend/prompts.json}") String promptsPath,
                              @Value("${nova.storage.blacklist-path:../backend/blacklist.json}") String blacklistPath) {
        this.promptMapper = promptMapper;
        this.blacklistMapper = blacklistMapper;
        this.objectMapper = objectMapper;
        this.promptsPath = Path.of(promptsPath);
        this.blacklistPath = Path.of(blacklistPath);
    }

    // ===== seed (first startup) =====

    @PostConstruct
    public void seedIfEmpty() {
        seedPromptsIfEmpty();
        seedBlacklistIfEmpty();
    }

    private void seedPromptsIfEmpty() {
        try {
            if (promptMapper.selectCount(new LambdaQueryWrapper<>()) > 0) {
                return; // admin data already present — never reseed over it
            }
            if (!Files.exists(promptsPath)) {
                log.info("[gallery] prompts 表为空且种子文件不存在，跳过导入 (path={})", promptsPath);
                return;
            }
            String raw = Files.readString(promptsPath, StandardCharsets.UTF_8);
            JsonNode array = objectMapper.readTree(raw);
            if (!array.isArray()) {
                log.warn("[gallery] prompts 种子文件格式无效（应为数组），跳过导入");
                return;
            }
            int imported = 0;
            for (JsonNode node : array) {
                PromptEntity entity = new PromptEntity();
                entity.setId(UUID.randomUUID());
                entity.setTitle(node.path("title").asText(""));
                entity.setContent(node.path("content").asText(""));
                entity.setType(node.path("type").asInt(1));
                entity.setEnabled(true);
                entity.setSortOrder(imported);
                entity.setCreatedAt(Instant.now());
                entity.setUpdatedAt(Instant.now());
                if (!entity.getTitle().isBlank()) {
                    promptMapper.insert(entity);
                    imported++;
                }
            }
            log.info("[gallery] prompts 种子导入完成: {} 条 (file={})", imported, promptsPath);
        } catch (Exception e) {
            log.warn("[gallery] prompts 种子导入失败（文件兜底保持可用）: {}", e.getMessage());
        }
    }

    private void seedBlacklistIfEmpty() {
        try {
            if (blacklistMapper.selectCount(new LambdaQueryWrapper<>()) > 0) {
                return;
            }
            if (!Files.exists(blacklistPath)) {
                log.info("[gallery] blacklist 表为空且种子文件不存在，跳过导入 (path={})", blacklistPath);
                return;
            }
            String raw = Files.readString(blacklistPath, StandardCharsets.UTF_8);
            JsonNode data = objectMapper.readTree(raw);
            JsonNode keywords = data.path("keywords");
            int imported = 0;
            if (keywords.isArray()) {
                for (JsonNode keyword : keywords) {
                    String value = keyword.asText("").trim();
                    if (value.isEmpty()) {
                        continue;
                    }
                    BlacklistEntity entity = new BlacklistEntity();
                    entity.setId(UUID.randomUUID());
                    entity.setKeyword(value);
                    entity.setCreatedAt(Instant.now());
                    try {
                        blacklistMapper.insert(entity);
                        imported++;
                    } catch (Exception duplicate) {
                        // UNIQUE constraint — skip dupes already seeded
                    }
                }
            }
            log.info("[gallery] blacklist 种子导入完成: {} 条 (file={})", imported, blacklistPath);
        } catch (Exception e) {
            log.warn("[gallery] blacklist 种子导入失败（文件兜底保持可用）: {}", e.getMessage());
        }
    }

    // ===== public reads (DB first, file fallback) =====

    /** All prompts for the public endpoint — DB first, file fallback on failure. */
    public List<PromptRow> prompts() {
        try {
            List<PromptEntity> rows = promptMapper.selectList(new LambdaQueryWrapper<PromptEntity>()
                    .orderByAsc(PromptEntity::getSortOrder)
                    .orderByAsc(PromptEntity::getCreatedAt));
            return rows.stream()
                    .map(r -> new PromptRow(r.getId(), r.getTitle(), r.getContent(),
                            r.getType() == null ? 1 : r.getType(),
                            !Boolean.FALSE.equals(r.getEnabled()),
                            r.getSortOrder() == null ? 0 : r.getSortOrder()))
                    .toList();
        } catch (Exception e) {
            log.warn("[gallery] prompts DB 读取失败，回退文件兜底: {}", e.getMessage());
            return promptsFromFile();
        }
    }

    /** All blacklist keywords — DB first, file fallback on failure. */
    public List<String> blacklistKeywords() {
        try {
            List<BlacklistEntity> rows = blacklistMapper.selectList(new LambdaQueryWrapper<BlacklistEntity>()
                    .orderByAsc(BlacklistEntity::getKeyword));
            return rows.stream().map(BlacklistEntity::getKeyword).toList();
        } catch (Exception e) {
            log.warn("[gallery] blacklist DB 读取失败，回退文件兜底: {}", e.getMessage());
            return keywordsFromFile();
        }
    }

    // ===== admin CRUD — prompts =====

    public List<PromptRow> listPrompts() {
        return prompts();
    }

    public PromptRow createPrompt(String title, String content, Integer type, Boolean enabled, Integer sortOrder) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        PromptEntity entity = new PromptEntity();
        entity.setId(UUID.randomUUID());
        entity.setTitle(title.trim());
        entity.setContent(content);
        entity.setType(type == null || type < 1 || type > 2 ? 1 : type);
        entity.setEnabled(enabled == null || enabled);
        entity.setSortOrder(sortOrder == null ? 0 : sortOrder);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        promptMapper.insert(entity);
        return toRow(entity);
    }

    public PromptRow updatePrompt(UUID id, String title, String content, Integer type, Boolean enabled, Integer sortOrder) {
        PromptEntity existing = promptMapper.selectById(id);
        if (existing == null) {
            throw new com.nova.studio.infra.HttpErrorException(404, "NOT_FOUND", "提示词不存在");
        }
        if (title != null && !title.isBlank()) {
            existing.setTitle(title.trim());
        }
        if (content != null && !content.isBlank()) {
            existing.setContent(content);
        }
        if (type != null) {
            existing.setType(type < 1 || type > 2 ? 1 : type);
        }
        if (enabled != null) {
            existing.setEnabled(enabled);
        }
        if (sortOrder != null) {
            existing.setSortOrder(sortOrder);
        }
        existing.setUpdatedAt(Instant.now());
        promptMapper.updateById(existing);
        return toRow(existing);
    }

    public void deletePrompt(UUID id) {
        if (promptMapper.deleteById(id) == 0) {
            throw new com.nova.studio.infra.HttpErrorException(404, "NOT_FOUND", "提示词不存在");
        }
    }

    // ===== admin CRUD — blacklist =====

    public List<KeywordRow> listKeywords() {
        List<BlacklistEntity> rows = blacklistMapper.selectList(new LambdaQueryWrapper<BlacklistEntity>()
                .orderByAsc(BlacklistEntity::getKeyword));
        return rows.stream().map(r -> new KeywordRow(r.getId(), r.getKeyword())).toList();
    }

    public KeywordRow addKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("关键词不能为空");
        }
        String value = keyword.trim();
        boolean exists = blacklistMapper.selectCount(new LambdaQueryWrapper<BlacklistEntity>()
                .eq(BlacklistEntity::getKeyword, value)) > 0;
        if (exists) {
            throw new com.nova.studio.infra.HttpErrorException(409, "DUPLICATE_KEYWORD", "关键词已存在");
        }
        BlacklistEntity entity = new BlacklistEntity();
        entity.setId(UUID.randomUUID());
        entity.setKeyword(value);
        entity.setCreatedAt(Instant.now());
        blacklistMapper.insert(entity);
        return new KeywordRow(entity.getId(), entity.getKeyword());
    }

    public void deleteKeyword(String keyword) {
        int removed = blacklistMapper.delete(new LambdaQueryWrapper<BlacklistEntity>()
                .eq(BlacklistEntity::getKeyword, keyword));
        if (removed == 0) {
            throw new com.nova.studio.infra.HttpErrorException(404, "NOT_FOUND", "关键词不存在");
        }
    }

    // ===== file fallback internals =====

    private List<PromptRow> promptsFromFile() {
        try {
            if (!Files.exists(promptsPath)) {
                return List.of();
            }
            String raw = Files.readString(promptsPath, StandardCharsets.UTF_8);
            JsonNode array = objectMapper.readTree(raw);
            if (!array.isArray()) {
                return List.of();
            }
            List<PromptRow> result = new ArrayList<>();
            int order = 0;
            for (JsonNode node : array) {
                String title = node.path("title").asText("");
                if (title.isBlank()) {
                    continue;
                }
                result.add(new PromptRow(null, title, node.path("content").asText(""),
                        node.path("type").asInt(1), true, order++));
            }
            return result;
        } catch (Exception e) {
            log.warn("[gallery] prompts 文件兜底读取失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> keywordsFromFile() {
        try {
            if (!Files.exists(blacklistPath)) {
                return List.of();
            }
            String raw = Files.readString(blacklistPath, StandardCharsets.UTF_8);
            JsonNode data = objectMapper.readTree(raw);
            JsonNode keywords = data.path("keywords");
            List<String> result = new ArrayList<>();
            if (keywords.isArray()) {
                for (JsonNode keyword : keywords) {
                    String value = keyword.asText("").trim();
                    if (!value.isEmpty()) {
                        result.add(value);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[gallery] blacklist 文件兜底读取失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static PromptRow toRow(PromptEntity e) {
        return new PromptRow(e.getId(), e.getTitle(), e.getContent(),
                e.getType() == null ? 1 : e.getType(),
                !Boolean.FALSE.equals(e.getEnabled()),
                e.getSortOrder() == null ? 0 : e.getSortOrder());
    }
}
