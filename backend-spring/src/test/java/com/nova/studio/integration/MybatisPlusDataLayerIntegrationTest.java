package com.nova.studio.integration;

import com.nova.studio.auth.UserRepository;
import com.nova.studio.settings.ModelRepository;
import com.nova.studio.settings.SettingsRepository;
import com.nova.studio.task.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIN-16 (ADR-11) — behavior-equivalence regression for the data-access layer
 * migration (JdbcTemplate Repository → MyBatis-Plus Mapper, strategy A:
 * Repository public signatures unchanged). Every assertion exercises the
 * repository's public API against the real PG server and documents the exact
 * behavior the mapper-backed implementation must preserve.
 *
 * <p>Runs only when {@code DB_HOST} is set (exported from {@code .env} by
 * {@code scripts/run-full-tests.sh}); skipped otherwise.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class MybatisPlusDataLayerIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SettingsRepository settingsRepository;

    @Autowired
    private ModelRepository modelRepository;

    @Autowired
    private TaskRepository taskRepository;

    private final List<Runnable> cleanup = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        // Reverse order: children before parents.
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            try {
                cleanup.get(i).run();
            } catch (Exception ignored) {
                // best-effort cleanup on a shared dev DB
            }
        }
        cleanup.clear();
    }

    // ===== users =====

    @Test
    void userCrudRoundTrip() {
        UUID id = UUID.randomUUID();
        String username = "win16_" + id.toString().substring(0, 8);
        cleanup.add(() -> userRepository.deleteByIdForTest(id));

        assertThat(userRepository.existsByUsername(username)).isFalse();
        UUID inserted = userRepository.insert(username, "hash", "user");
        assertThat(inserted).isNotNull();
        cleanup.add(() -> userRepository.deleteByIdForTest(inserted));

        assertThat(userRepository.existsByUsername(username)).isTrue();
        assertThat(userRepository.findByUsername(username))
                .isPresent()
                .get()
                .satisfies(row -> {
                    assertThat(row.username()).isEqualTo(username);
                    assertThat(row.passwordHash()).isEqualTo("hash");
                    assertThat(row.role()).isEqualTo("user");
                    assertThat(row.createdAt()).isNotNull();
                    assertThat(row.updatedAt()).isNotNull();
                });
        assertThat(userRepository.findById(inserted)).isPresent();
    }

    // ===== settings (composite PK — upsert must keep ON CONFLICT semantics) =====

    @Test
    void settingsUpsertReadDeleteRoundTrip() {
        UUID userId = freshUserId();

        settingsRepository.upsert(userId, "workbench.t2i.prompt", "{\"v\":1}", "json");
        Map<String, String> all = settingsRepository.findAllByUser(userId);
        // PG JSONB canonicalizes whitespace ({v:1} → {v: 1}); compare parsed JSON.
        assertThat(all).containsKey("workbench.t2i.prompt");
        assertThat(parseJson(all.get("workbench.t2i.prompt")).get("v").asInt()).isEqualTo(1);

        // Idempotent upsert overwrites the value (ON CONFLICT DO UPDATE).
        settingsRepository.upsert(userId, "workbench.t2i.prompt", "{\"v\":2}", "json");
        assertThat(parseJson(settingsRepository.findAllByUser(userId).get("workbench.t2i.prompt")).get("v").asInt())
                .isEqualTo(2);

        // Other users' rows are untouched.
        UUID other = freshUserId();
        settingsRepository.upsert(other, "limit.maxQueueSize", "99", "json");
        assertThat(settingsRepository.findAllByUser(userId)).doesNotContainKey("limit.maxQueueSize");

        settingsRepository.delete(userId, "workbench.t2i.prompt");
        assertThat(settingsRepository.findAllByUser(userId)).doesNotContainKey("workbench.t2i.prompt");
    }

    // ===== models =====

    @Test
    void modelCrudAndScoping() {
        UUID userId = freshUserId();
        UUID other = freshUserId();
        String name = "win16-model-" + UUID.randomUUID().toString().substring(0, 8);

        UUID id = modelRepository.insert(userId, "image", "openai", name, "gpt-image-2",
                "https://api.openai.com/v1", "enc-cipher", "{\"max_ref_images\":2}", "preset-1");
        cleanup.add(() -> modelRepository.delete(id, userId));

        List<ModelRepository.ModelRow> rows = modelRepository.findByUserId(userId);
        assertThat(rows).extracting(ModelRepository.ModelRow::id).contains(id);
        assertThat(rows).extracting(ModelRepository.ModelRow::apiKeyEnc).contains("enc-cipher");

        assertThat(modelRepository.findByIdAndUser(id, userId)).isPresent();
        // Isolation: the other user cannot see or touch it.
        assertThat(modelRepository.findByIdAndUser(id, other)).isEmpty();
        assertThat(modelRepository.countByUser(other)).isZero();

        assertThat(modelRepository.existsName(userId, "image", name, null)).isTrue();
        assertThat(modelRepository.existsName(userId, "image", name, id)).isFalse();
        assertThat(modelRepository.existsName(userId, "image", "nope", null)).isFalse();

        modelRepository.update(id, userId, "image", "openai", name, "gpt-image-2",
                "https://api.openai.com/v1", null, "{\"max_ref_images\":3}", "preset-1");
        assertThat(modelRepository.findByIdAndUser(id, userId))
                .get()
                .satisfies(row -> assertThat(parseJson(row.capabilitiesJson()).get("max_ref_images").asInt()).isEqualTo(3));

        // update() must also be able to write a NULL apiKeyEnc (key not retyped).
        assertThat(modelRepository.findByIdAndUser(id, userId)).get()
                .satisfies(row -> assertThat(row.apiKeyEnc()).isNull());

        assertThat(modelRepository.delete(id, userId)).isTrue();
        assertThat(modelRepository.delete(id, userId)).isFalse();
    }

    // ===== tasks =====

    @Test
    void taskInsertStatusQueueExpireDeleteRoundTrip() {
        String id = UUID.randomUUID().toString();
        UUID userId = freshUserId();
        String nowIso = Instant.now().toString();

        taskRepository.insertTaskAndItems(id, userId, TaskRepository.STATUS_QUEUED,
                "text-to-image", "{\"mode\":\"text-to-image\"}", nowIso, 2);
        cleanup.add(() -> taskRepository.deleteTaskAndItems(id));

        assertThat(taskRepository.exists(id)).isTrue();
        assertThat(taskRepository.findById(id))
                .isPresent()
                .get()
                .satisfies(row -> {
                    assertThat(row.userId()).isEqualTo(userId.toString());
                    assertThat(row.status()).isEqualTo(TaskRepository.STATUS_QUEUED);
                    assertThat(parseJson(row.requestJson()).get("mode").asText()).isEqualTo("text-to-image");
                    assertThat(row.createdAt()).isNotNull();
                });

        taskRepository.updateStatus(id, TaskRepository.STATUS_PROCESSING);
        assertThat(taskRepository.findById(id)).get()
                .satisfies(row -> assertThat(row.status()).isEqualTo(TaskRepository.STATUS_PROCESSING));

        taskRepository.updateItemStatus(id, 0, TaskRepository.STATUS_COMPLETED, Instant.now().toString());
        taskRepository.updateItemImageData(id, 1, TaskRepository.STATUS_COMPLETED,
                "[\"URL:/api/nova/images/x/1/0.png\"]", Instant.now().toString());

        // Queue stats grouping counts queued + processing ('排队中' and 'queued').
        Map<String, Long> counts = taskRepository.countByQueueStatuses();
        assertThat(counts.get(TaskRepository.STATUS_PROCESSING)).isGreaterThanOrEqualTo(1L);

        // TTL expiry: an expired task is picked up by findExpired.
        taskRepository.updateExpiresAt(id, Instant.now().minusSeconds(1));
        assertThat(taskRepository.findExpired(Instant.now())).contains(id);

        // normalizeLegacyQueued rewrites 'queued' → '排队中' (Node startup behavior).
        taskRepository.updateStatus(id, TaskRepository.STATUS_LEGACY_QUEUED);
        taskRepository.normalizeLegacyQueued();
        assertThat(taskRepository.findById(id)).get()
                .satisfies(row -> assertThat(row.status()).isEqualTo(TaskRepository.STATUS_QUEUED));

        // markInterruptedTasksFailed fails queued/processing tasks and returns their ids.
        taskRepository.updateStatus(id, TaskRepository.STATUS_QUEUED);
        List<String> interrupted = taskRepository.markInterruptedTasksFailed(
                "重启中断", Instant.now().toString(), Instant.now().toString());
        assertThat(interrupted).contains(id);
        assertThat(taskRepository.findById(id)).get()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo(TaskRepository.STATUS_FAILED);
                    assertThat(row.error()).isEqualTo("重启中断");
                });
    }

    @Test
    void completeAndFailTaskPreserveResultAndError() {
        String id = UUID.randomUUID().toString();
        UUID userId = freshUserId();
        String nowIso = Instant.now().toString();
        taskRepository.insertTaskAndItems(id, userId, TaskRepository.STATUS_QUEUED,
                "text-to-image", "{}", nowIso, 1);
        cleanup.add(() -> taskRepository.deleteTaskAndItems(id));

        taskRepository.completeTask(id, "{\"images\":[\"URL:/x.png\"]}", null,
                Instant.now().toString(), Instant.now().plusSeconds(60).toString());
        assertThat(taskRepository.findById(id)).get()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo(TaskRepository.STATUS_COMPLETED);
                    assertThat(row.resultJson()).contains("\"images\"");
                    assertThat(row.completedAt()).isNotNull();
                    assertThat(row.expiresAt()).isNotNull();
                });

        taskRepository.failTask(id, "boom", Instant.now().toString(), Instant.now().plusSeconds(60).toString());
        assertThat(taskRepository.findById(id)).get()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo(TaskRepository.STATUS_FAILED);
                    assertThat(row.error()).isEqualTo("boom");
                });
    }

    // ===== helpers =====

    private tools.jackson.databind.JsonNode parseJson(String raw) {
        try {
            return new tools.jackson.databind.ObjectMapper().readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("bad json: " + raw, e);
        }
    }

    /** Registers a throwaway user (FK parent for settings/models rows) and cleans up. */
    private UUID freshUserId() {
        UUID id = UUID.randomUUID();
        String username = "win16_" + id.toString().substring(0, 8);
        UUID inserted = userRepository.insert(username, "hash", "user");
        cleanup.add(() -> userRepository.deleteByIdForTest(inserted));
        return inserted;
    }
}
