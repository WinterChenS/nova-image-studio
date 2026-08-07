package com.nova.studio.project;

import com.nova.studio.asset.AssetService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.SettingsService;
import com.nova.studio.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WIN-22 (F-1..F-8 / D.1) — project service: default-project lazy creation
 * (R-1), CRUD, ownership isolation (N-1), archive, delete force semantics
 * (ADR-18) and set-default persistence.
 */
class ProjectServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ProjectRepository repository;
    private SettingsService settingsService;
    private TaskRepository taskRepository;
    private AssetService assetService;
    private ProjectService service;

    private ProjectRepository.ProjectRow row(String id, String userId, String name, boolean archived) {
        return new ProjectRepository.ProjectRow(id, userId, name, null, archived, 0, false,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"));
    }

    @BeforeEach
    void setUp() {
        repository = mock(ProjectRepository.class);
        settingsService = mock(SettingsService.class);
        taskRepository = mock(TaskRepository.class);
        assetService = mock(AssetService.class);
        service = new ProjectService(repository, settingsService, taskRepository, assetService);
    }

    @Test
    void ensureDefaultProjectCreatesAndPersistsWhenNoSetting() {
        when(settingsService.getAll(USER_ID)).thenReturn(Map.of());
        when(repository.insert(eq(USER_ID), eq("默认项目"), eq(null), eq(0))).thenReturn("p-default");
        when(repository.findById("p-default")).thenReturn(Optional.of(row("p-default", USER_ID.toString(), "默认项目", false)));

        ProjectRepository.ProjectRow defaultProject = service.ensureDefaultProject(USER_ID);

        assertThat(defaultProject.name()).isEqualTo("默认项目");
        verify(settingsService).putAll(eq(USER_ID), any(ObjectNode.class));
    }

    @Test
    void ensureDefaultProjectReusesStoredDefault() {
        when(settingsService.getAll(USER_ID)).thenReturn(Map.of("workbench.defaultProjectId", "p-stored"));
        when(repository.findById("p-stored")).thenReturn(Optional.of(row("p-stored", USER_ID.toString(), "我的项目", false)));

        ProjectRepository.ProjectRow result = service.ensureDefaultProject(USER_ID);

        assertThat(result.id()).isEqualTo("p-stored");
        verify(repository, never()).insert(any(), any(), any(), any());
    }

    @Test
    void requireOwnedReturns404ForCrossUser() {
        when(repository.findById("p-other")).thenReturn(Optional.of(row("p-other", OTHER_ID.toString(), "他人项目", false)));
        assertThatThrownBy(() -> service.requireOwned(USER_ID, "p-other"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    void createValidatesName() {
        assertThatThrownBy(() -> service.create(USER_ID, "  ", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("项目名称不能为空");
    }

    @Test
    void deleteDefaultProjectRejected() {
        when(repository.findById("p-default")).thenReturn(Optional.of(row("p-default", USER_ID.toString(), "默认项目", false)));
        when(settingsService.getAll(USER_ID)).thenReturn(Map.of("workbench.defaultProjectId", "p-default"));
        assertThatThrownBy(() -> service.delete(USER_ID, "p-default", false))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(400);
                    assertThat(e.getCode()).isEqualTo("DEFAULT_PROJECT");
                });
        verify(repository, never()).delete(anyString());
    }

    @Test
    void deleteWithAssetsRequiresForce() {
        when(repository.findById("p-1")).thenReturn(Optional.of(row("p-1", USER_ID.toString(), "项目", false)));
        when(settingsService.getAll(USER_ID)).thenReturn(Map.of());
        when(assetService.countByProject(USER_ID, "p-1")).thenReturn(3L);
        assertThatThrownBy(() -> service.delete(USER_ID, "p-1", false))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("PROJECT_NOT_EMPTY");
                });
        verify(repository, never()).delete(anyString());
    }

    @Test
    void deleteForceCascadesAssetsAndClearsTaskProject() {
        when(repository.findById("p-1")).thenReturn(Optional.of(row("p-1", USER_ID.toString(), "项目", false)));
        when(settingsService.getAll(USER_ID)).thenReturn(Map.of());
        when(assetService.countByProject(USER_ID, "p-1")).thenReturn(2L);
        service.delete(USER_ID, "p-1", true);
        verify(assetService).deleteByProject(USER_ID, "p-1");
        verify(taskRepository).clearProjectId("p-1");
        verify(repository).delete("p-1");
    }

    @Test
    void updateArchivesAndRenames() {
        when(repository.findById("p-1")).thenReturn(Optional.of(row("p-1", USER_ID.toString(), "项目", false)));
        when(repository.findById("p-1")).thenReturn(Optional.of(row("p-1", USER_ID.toString(), "新名称", true)));
        var updated = service.update(USER_ID, "p-1", "新名称", null, true, null, null);
        assertThat(updated.name()).isEqualTo("新名称");
        assertThat(updated.archived()).isTrue();
        verify(repository).update(eq("p-1"), eq("新名称"), any(), eq(true), any(), any());
    }

    @Test
    void setDefaultPersistsViaSettings() {
        when(repository.findById("p-2")).thenReturn(Optional.of(row("p-2", USER_ID.toString(), "项目", false)));
        service.setDefault(USER_ID, "p-2");
        verify(settingsService).putAll(eq(USER_ID), any(ObjectNode.class));
    }

    @Test
    void ownsIsFalseForUnknownProject() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        assertThat(service.owns(USER_ID, "nope")).isFalse();
    }
}
