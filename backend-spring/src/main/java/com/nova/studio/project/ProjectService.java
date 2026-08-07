package com.nova.studio.project;

import com.nova.studio.asset.AssetService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.SettingsService;
import com.nova.studio.task.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * WIN-22 (F-1..F-8 / D.1) — per-user private projects: CRUD, default-project
 * lazy creation (R-1), archive/restore, stats, set-default (persisted via the
 * existing {@code workbench.defaultProjectId} settings allowlist, F-4) and
 * delete with {@code force} cascade semantics (ADR-18).
 *
 * <p>Ownership is enforced on every mutation/read (cross-user access → 404,
 * N-1). The default project cannot be deleted (400); non-default projects
 * refuse deletion when they still hold assets unless {@code force=true}, which
 * cascades: assets are deleted (DB + objects via {@link AssetService}) and the
 * project's tasks fall back to NULL (未分类, G-4).
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    public static final String DEFAULT_PROJECT_NAME = "默认项目";
    public static final String SETTING_DEFAULT_PROJECT = "workbench.defaultProjectId";

    private final ProjectRepository repository;
    private final SettingsService settingsService;
    private final TaskRepository taskRepository;
    private final AssetService assetService;

    public ProjectService(ProjectRepository repository,
                          SettingsService settingsService,
                          TaskRepository taskRepository,
                          @Lazy AssetService assetService) {
        this.repository = repository;
        this.settingsService = settingsService;
        this.taskRepository = taskRepository;
        this.assetService = assetService;
    }

    /** Public row shape with aggregate stats (F-6, P1 — included in list). */
    public record ProjectDetail(String id, String name, String description, boolean archived,
                                int sortOrder, boolean autoSave, Instant createdAt, Instant updatedAt,
                                long taskCount, long assetCount, Instant lastActivityAt) {
    }

    // ===== default project (R-1) =====

    /**
     * Lazy, idempotent default-project resolution: the project referenced by
     * {@code workbench.defaultProjectId} (when still owned) wins; otherwise a
     * fresh 「默认项目」 is created and persisted as the default. Called from
     * project listing, task creation and asset creation (ADR-17 fallback).
     */
    public synchronized ProjectRepository.ProjectRow ensureDefaultProject(UUID userId) {
        Object defaultId = settingsService.getAll(userId).get(SETTING_DEFAULT_PROJECT);
        if (defaultId instanceof String id && !id.isBlank()) {
            Optional<ProjectRepository.ProjectRow> owned = repository.findById(id)
                    .filter(row -> row.userId().equals(userId.toString()));
            if (owned.isPresent()) {
                return owned.get();
            }
        }
        // create + persist as default
        String id = repository.insert(userId, DEFAULT_PROJECT_NAME, null, 0);
        setDefaultProjectId(userId, id);
        log.info("[project] 已为用户 {} 懒创建默认项目 {}", userId, id);
        return repository.findById(id).orElseThrow();
    }

    /** Persists the user's default project id (existing workbench.* allowlist). */
    public void setDefaultProjectId(UUID userId, String projectId) {
        ObjectNode node = new tools.jackson.databind.ObjectMapper().createObjectNode();
        node.put(SETTING_DEFAULT_PROJECT, projectId);
        settingsService.putAll(userId, node);
    }

    // ===== read =====

    public List<ProjectDetail> listByUser(UUID userId, boolean includeArchived) {
        ensureDefaultProject(userId);
        List<ProjectRepository.ProjectRow> rows = includeArchived
                ? repository.findByUser(userId, true)
                : repository.findByUser(userId, false);
        List<ProjectDetail> result = new ArrayList<>();
        for (ProjectRepository.ProjectRow row : rows) {
            result.add(toDetail(userId, row));
        }
        return result;
    }

    public ProjectDetail getDetail(UUID userId, String id) {
        ProjectRepository.ProjectRow row = requireOwned(userId, id);
        return toDetail(userId, row);
    }

    public ProjectRepository.ProjectRow requireOwned(UUID userId, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new HttpErrorException(404, "NOT_FOUND", "项目不存在");
        }
        return repository.findById(projectId)
                .filter(row -> row.userId().equals(userId.toString()))
                .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "项目不存在"));
    }

    /** Ownership check used by asset/task services; false for unknown/cross-user ids. */
    public boolean owns(UUID userId, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        return repository.findById(projectId)
                .filter(row -> row.userId().equals(userId.toString()))
                .isPresent();
    }

    public String defaultProjectId(UUID userId) {
        return ensureDefaultProject(userId).id();
    }

    // ===== write =====

    public ProjectRepository.ProjectRow create(UUID userId, String name, String description, Integer sortOrder) {
        String normalized = normalizeName(name);
        String id = repository.insert(userId, normalized, blankToNull(description), sortOrder);
        return repository.findById(id).orElseThrow();
    }

    public ProjectRepository.ProjectRow update(UUID userId, String id, String name, String description,
                                               Boolean archived, Integer sortOrder, Boolean autoSave) {
        ProjectRepository.ProjectRow row = requireOwned(userId, id);
        String nextName = name != null ? normalizeName(name) : row.name();
        String nextDescription = description != null ? blankToNull(description) : row.description();
        Boolean nextArchived = archived != null ? archived : row.archived();
        Integer nextSortOrder = sortOrder != null ? sortOrder : row.sortOrder();
        Boolean nextAutoSave = autoSave != null ? autoSave : row.autoSave();
        repository.update(id, nextName, nextDescription, nextArchived, nextSortOrder, nextAutoSave);
        return repository.findById(id).orElseThrow();
    }

    /**
     * Delete (ADR-18). Rules: default project → 400; assets present and
     * {@code force=false} → 409 (prompt to migrate first); {@code force=true} →
     * cascade delete assets (DB + objects) then set the project's tasks'
     * {@code project_id} to NULL (未分类), then remove the row.
     */
    public void delete(UUID userId, String id, boolean force) {
        ProjectRepository.ProjectRow row = requireOwned(userId, id);
        if (isDefaultProject(userId, row.id())) {
            throw new HttpErrorException(400, "DEFAULT_PROJECT", "默认项目不可删除");
        }
        long assetCount = assetService.countByProject(userId, row.id());
        if (assetCount > 0 && !force) {
            throw new HttpErrorException(409, "PROJECT_NOT_EMPTY",
                    "项目内还有 " + assetCount + " 个素材，请先迁移或使用强制删除", null);
        }
        if (force) {
            assetService.deleteByProject(userId, row.id());
        }
        taskRepository.clearProjectId(row.id());
        repository.delete(row.id());
        log.info("[project] 已删除项目: user={}, project={}, force={}", userId, id, force);
    }

    public void setDefault(UUID userId, String id) {
        requireOwned(userId, id);
        setDefaultProjectId(userId, id);
    }

    public boolean isDefaultProject(UUID userId, String projectId) {
        Object defaultId = settingsService.getAll(userId).get(SETTING_DEFAULT_PROJECT);
        return defaultId instanceof String s && s.equals(projectId);
    }

    // ===== stats (F-6, P1 — computed from tasks/assets counts) =====

    private ProjectDetail toDetail(UUID userId, ProjectRepository.ProjectRow row) {
        long taskCount = taskRepository.countByUserAndProject(userId, row.id());
        long assetCount = assetService.countByProject(userId, row.id());
        Instant lastActivity = assetService.lastActivityByProject(userId, row.id());
        Instant taskActivity = taskRepository.lastActivityByProject(userId, row.id());
        Instant last = max(lastActivity, taskActivity, row.updatedAt());
        return new ProjectDetail(row.id(), row.name(), row.description(), row.archived(),
                row.sortOrder(), row.autoSave(), row.createdAt(), row.updatedAt(),
                taskCount, assetCount, last);
    }

    private static Instant max(Instant... values) {
        Instant result = null;
        for (Instant v : values) {
            if (v != null && (result == null || v.isAfter(result))) {
                result = v;
            }
        }
        return result;
    }

    private static String normalizeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("项目名称长度不能超过 64 字");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Public JSON shape for the frontend (camelCase, no internals). */
    public static Map<String, Object> toJson(ProjectDetail detail) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", detail.id());
        map.put("name", detail.name());
        map.put("description", detail.description());
        map.put("archived", detail.archived());
        map.put("sortOrder", detail.sortOrder());
        map.put("autoSave", detail.autoSave());
        map.put("createdAt", detail.createdAt() == null ? null : detail.createdAt().toString());
        map.put("updatedAt", detail.updatedAt() == null ? null : detail.updatedAt().toString());
        map.put("taskCount", detail.taskCount());
        map.put("assetCount", detail.assetCount());
        map.put("lastActivityAt", detail.lastActivityAt() == null ? null : detail.lastActivityAt().toString());
        return map;
    }
}
