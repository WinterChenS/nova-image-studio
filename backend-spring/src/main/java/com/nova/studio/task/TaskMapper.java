package com.nova.studio.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WIN-16 (ADR-11) — MyBatis-Plus mapper for the {@code tasks} table. Simple
 * lookups go through {@link BaseMapper} (findById/exists); every state-machine
 * SQL is migrated <b>verbatim</b> from the JdbcTemplate {@code TaskRepository}
 * because the queue semantics ('排队中' + 'queued' dual status, UTC
 * {@code expires_at} comparisons, Node startup normalization) must not drift
 * (ARCH C.3.2.5 — "行为等价优先，不强行 wrapper 化").
 */
@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {

    @Insert("""
            INSERT INTO tasks (id, user_id, status, mode, request_json, created_at)
            VALUES (#{id}, #{userId}, #{status}, #{mode}, #{requestJson}::jsonb, #{createdAt})
            """)
    void insertTask(@Param("id") String id, @Param("userId") UUID userId,
                    @Param("status") String status, @Param("mode") String mode,
                    @Param("requestJson") String requestJson, @Param("createdAt") Instant createdAt);

    @Insert("""
            INSERT INTO task_items (task_id, item_index, status, created_at)
            VALUES (#{taskId}, #{itemIndex}, #{status}, #{createdAt})
            """)
    void insertItem(@Param("taskId") String taskId, @Param("itemIndex") int itemIndex,
                    @Param("status") String status, @Param("createdAt") Instant createdAt);

    @Update("UPDATE tasks SET status = #{status} WHERE id = #{id}")
    void updateStatus(@Param("id") String id, @Param("status") String status);

    @Update("""
            UPDATE task_items SET status = #{status}, completed_at = #{completedAt}
            WHERE task_id = #{taskId} AND item_index = #{itemIndex}
            """)
    void updateItemStatus(@Param("taskId") String taskId, @Param("itemIndex") int itemIndex,
                          @Param("status") String status, @Param("completedAt") Instant completedAt);

    @Update("""
            UPDATE task_items SET created_at = #{createdAt}
            WHERE task_id = #{taskId} AND item_index = #{itemIndex}
            """)
    void updateItemCreatedAt(@Param("taskId") String taskId, @Param("itemIndex") int itemIndex,
                             @Param("createdAt") Instant createdAt);

    /** Node runTask: items flip to processing with created_at refreshed. */
    @Update("""
            UPDATE task_items SET status = #{status}, created_at = #{createdAt}
            WHERE task_id = #{taskId} AND item_index = #{itemIndex}
            """)
    void updateItemProcessing(@Param("taskId") String taskId, @Param("itemIndex") int itemIndex,
                              @Param("status") String status, @Param("createdAt") Instant createdAt);

    /** Marks a task completed with result_json / warning / completed_at / expires_at. */
    @Update("""
            UPDATE tasks SET status = #{status}, result_json = #{resultJson}::jsonb, warning = #{warning},
                completed_at = #{completedAt}, expires_at = #{expiresAt}
            WHERE id = #{id}
            """)
    void completeTask(@Param("id") String id, @Param("status") String status,
                      @Param("resultJson") String resultJson, @Param("warning") String warning,
                      @Param("completedAt") Instant completedAt, @Param("expiresAt") Instant expiresAt);

    /** Marks a task failed with error / completed_at / expires_at. */
    @Update("""
            UPDATE tasks SET status = #{status}, error = #{error}, completed_at = #{completedAt},
                expires_at = #{expiresAt}
            WHERE id = #{id}
            """)
    void failTask(@Param("id") String id, @Param("status") String status, @Param("error") String error,
                  @Param("completedAt") Instant completedAt, @Param("expiresAt") Instant expiresAt);

    @Select("""
            SELECT id FROM tasks WHERE status IN (#{queued}, #{processing})
            """)
    List<String> findInterrupted(@Param("queued") String queued, @Param("processing") String processing);

    @Update("""
            UPDATE tasks SET status = #{failed}, error = #{error}, completed_at = #{completedAt},
                expires_at = #{expiresAt}
            WHERE status IN (#{queued}, #{processing})
            """)
    int failInterrupted(@Param("failed") String failed, @Param("error") String error,
                        @Param("completedAt") Instant completedAt, @Param("expiresAt") Instant expiresAt,
                        @Param("queued") String queued, @Param("processing") String processing);

    @Update("UPDATE tasks SET status = #{status} WHERE status = #{legacy}")
    int normalizeTaskStatus(@Param("status") String status, @Param("legacy") String legacy);

    @Update("UPDATE task_items SET status = #{status} WHERE status = #{legacy}")
    int normalizeItemStatus(@Param("status") String status, @Param("legacy") String legacy);

    @Update("UPDATE tasks SET expires_at = #{expiresAt} WHERE id = #{id}")
    void updateExpiresAt(@Param("id") String id, @Param("expiresAt") Instant expiresAt);

    @Update("""
            UPDATE task_items SET status = #{status}, image_data = #{imageData}, completed_at = #{completedAt}
            WHERE task_id = #{taskId} AND item_index = #{itemIndex}
            """)
    void updateItemImageData(@Param("taskId") String taskId, @Param("itemIndex") int itemIndex,
                             @Param("status") String status, @Param("imageData") String imageData,
                             @Param("completedAt") Instant completedAt);

    @Update("""
            UPDATE task_items SET status = #{status}, error = #{error}, completed_at = #{completedAt}
            WHERE task_id = #{taskId} AND item_index = #{itemIndex}
            """)
    void updateItemError(@Param("taskId") String taskId, @Param("itemIndex") int itemIndex,
                         @Param("status") String status, @Param("error") String error,
                         @Param("completedAt") Instant completedAt);

    /** Queue stats grouping — Node getQueueStats SQL (statuses '排队中'/'queued'/'processing'). */
    @Select("""
            SELECT status, COUNT(*) AS count FROM tasks
            WHERE status IN (#{queued}, #{legacyQueued}, #{processing})
            GROUP BY status
            """)
    List<Map<String, Object>> countByQueueStatuses(@Param("queued") String queued,
                                                   @Param("legacyQueued") String legacyQueued,
                                                   @Param("processing") String processing);

    @Select("""
            SELECT id FROM tasks WHERE expires_at IS NOT NULL AND expires_at <= #{now}
            """)
    List<String> findExpired(@Param("now") Instant now);

    @Delete("DELETE FROM task_items WHERE task_id = #{taskId}")
    int deleteItems(@Param("taskId") String taskId);

    @Delete("DELETE FROM tasks WHERE id = #{id}")
    int deleteTask(@Param("id") String id);
}
