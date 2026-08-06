package com.nova.studio.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WIN-22 — {@code projects} table access (per-user private projects). The
 * public row shape is {@link ProjectRow}; entities are converted at the
 * boundary so the service layer only sees immutable rows.
 */
@Repository
public class ProjectRepository {

    /** Public row shape (service/test contract). */
    public record ProjectRow(String id, String userId, String name, String description,
                             boolean archived, int sortOrder, boolean autoSave,
                             Instant createdAt, Instant updatedAt) {
    }

    private final ProjectMapper mapper;

    public ProjectRepository(ProjectMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<ProjectRow> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(ProjectRepository::toRow);
    }

    /** All projects of a user, ordered by sort_order then created_at. */
    public List<ProjectRow> findByUser(UUID userId, boolean includeArchived) {
        LambdaQueryWrapper<ProjectEntity> wrapper = new LambdaQueryWrapper<ProjectEntity>()
                .eq(ProjectEntity::getUserId, userId.toString())
                .orderByAsc(ProjectEntity::getSortOrder)
                .orderByAsc(ProjectEntity::getCreatedAt);
        if (!includeArchived) {
            wrapper.eq(ProjectEntity::getArchived, false);
        }
        return mapper.selectList(wrapper).stream().map(ProjectRepository::toRow).toList();
    }

    /** Any non-archived project of a user (used by the default-project fallback). */
    public List<ProjectRow> findActive(UUID userId) {
        return mapper.selectList(new LambdaQueryWrapper<ProjectEntity>()
                        .eq(ProjectEntity::getUserId, userId.toString())
                        .eq(ProjectEntity::getArchived, false)
                        .orderByAsc(ProjectEntity::getSortOrder)
                        .orderByAsc(ProjectEntity::getCreatedAt))
                .stream().map(ProjectRepository::toRow).toList();
    }

    public long countByUser(UUID userId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<ProjectEntity>()
                .eq(ProjectEntity::getUserId, userId.toString()));
        return count == null ? 0 : count;
    }

    /** Creates a project; returns the generated id. */
    public String insert(UUID userId, String name, String description, Integer sortOrder) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        ProjectEntity entity = new ProjectEntity();
        entity.setId(id);
        entity.setUserId(userId.toString());
        entity.setName(name);
        entity.setDescription(description);
        entity.setArchived(false);
        entity.setSortOrder(sortOrder == null ? 0 : sortOrder);
        entity.setAutoSave(false);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return id;
    }

    /** Selective update of mutable fields (name/description/archived/sortOrder/autoSave). */
    public void update(String id, String name, String description, Boolean archived,
                       Integer sortOrder, Boolean autoSave) {
        ProjectEntity patch = new ProjectEntity();
        patch.setId(id);
        patch.setName(name);
        patch.setDescription(description);
        patch.setArchived(archived);
        patch.setSortOrder(sortOrder);
        patch.setAutoSave(autoSave);
        patch.setUpdatedAt(Instant.now());
        mapper.updateById(patch);
    }

    public void delete(String id) {
        mapper.deleteById(id);
    }

    private static ProjectRow toRow(ProjectEntity e) {
        return new ProjectRow(e.getId(), e.getUserId(), e.getName(), e.getDescription(),
                Boolean.TRUE.equals(e.getArchived()),
                e.getSortOrder() == null ? 0 : e.getSortOrder(),
                Boolean.TRUE.equals(e.getAutoSave()),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
