package com.nova.studio.task;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T1.1/T1.7 — task serialization (Node serializeTask port): expired derivation
 * from expires_at, null fields omitted (JSON.stringify drops undefined), result
 * parsed from result_json.
 */
class TaskLookupServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskRepository repository = mock(TaskRepository.class);
    private final TaskLookupService service = new TaskLookupService(repository, mapper);

    @Test
    void queuedTaskOmitsNullFields() {
        TaskRepository.TaskRow row = new TaskRepository.TaskRow(
                "t1", null, TaskRepository.STATUS_QUEUED, "text-to-image",
                "{}", null, null, null, Instant.parse("2026-08-03T00:00:00Z"), null, null);
        when(repository.findById("t1")).thenReturn(Optional.of(row));

        Map<String, Object> msg = service.loadTaskMessage("t1");

        assertThat(msg).containsEntry("id", "t1")
                .containsEntry("status", TaskRepository.STATUS_QUEUED)
                .containsEntry("mode", "text-to-image")
                .containsEntry("createdAt", "2026-08-03T00:00:00Z");
        assertThat(msg).doesNotContainKeys("result", "error", "warning", "completedAt", "expiresAt");
    }

    @Test
    void completedTaskIncludesParsedResultAndWarning() {
        TaskRepository.TaskRow row = new TaskRepository.TaskRow(
                "t1", null, TaskRepository.STATUS_COMPLETED, "text-to-image",
                "{}", "{\"images\":[\"URL:/api/nova/images/t1/0\"]}", null,
                "1 张图片生成失败: boom",
                Instant.parse("2026-08-03T00:00:00Z"), Instant.parse("2026-08-03T00:01:00Z"),
                Instant.parse("2026-08-03T12:00:00Z"));
        when(repository.findById("t1")).thenReturn(Optional.of(row));

        Map<String, Object> msg = service.loadTaskMessage("t1");

        assertThat(msg.get("result")).isEqualTo(Map.of("images", java.util.List.of("URL:/api/nova/images/t1/0")));
        assertThat(msg.get("warning")).isEqualTo("1 张图片生成失败: boom");
    }

    @Test
    void expiredTaskDerivesExpiredStatus() {
        TaskRepository.TaskRow row = new TaskRepository.TaskRow(
                "t1", null, TaskRepository.STATUS_COMPLETED, "text-to-image",
                "{}", "{}", null, null, Instant.parse("2026-08-02T00:00:00Z"),
                Instant.parse("2026-08-02T00:01:00Z"), Instant.parse("2026-08-02T12:00:00Z"));
        when(repository.findById("t1")).thenReturn(Optional.of(row));

        Map<String, Object> msg = service.loadTaskMessage("t1");

        assertThat(msg).containsEntry("status", TaskRepository.STATUS_EXPIRED);
        assertThat(msg).containsEntry("error", "该任务已超出取回时间");
    }

    @Test
    void unknownTaskReturnsNull() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        assertThat(service.loadTaskMessage("missing")).isNull();
    }
}
