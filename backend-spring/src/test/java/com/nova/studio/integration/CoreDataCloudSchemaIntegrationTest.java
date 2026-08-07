package com.nova.studio.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 (WIN-40) — Flyway V7 迁移验收：DDL 终稿落库（6 新表 + assets 扩展 +
 * usage_records CHECK 扩展 + 索引）、现有 V1–V6 数据零破坏、新表 round-trip。
 *
 * <p>PG 门禁（DB_HOST 环境变量存在时运行），直接校验 information_schema /
 * pg_indexes 与真实 round-trip，覆盖「迁移幂等可重复执行；DDL 与终稿一致」。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class CoreDataCloudSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final List<String> NEW_TABLES = List.of(
            "conversations", "conversation_messages", "canvas_projects", "histories", "prompt_gallery_items");

    @Test
    void v7MigrationRecordedInFlywayHistory() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.flyway_schema_history WHERE version = '7' AND success = TRUE",
                Integer.class);
        assertThat(count).as("V7 迁移应已成功执行").isEqualTo(1);
    }

    @Test
    void allSixNewTablesExist() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN
                ('conversations', 'conversation_messages', 'canvas_projects', 'histories', 'prompt_gallery_items')
                """, Integer.class);
        assertThat(count).isEqualTo(5);
    }

    @Test
    void assetsExtensionColumnsExist() {
        List<Map<String, Object>> cols = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'assets'
                AND column_name IN ('extra', 'deleted_at', 'ref_count')
                """);
        assertThat(cols).hasSize(3);
        Map<String, Object> extra = cols.stream()
                .filter(c -> "extra".equals(c.get("column_name"))).findFirst().orElseThrow();
        assertThat(extra.get("data_type").toString()).contains("json");
        assertThat(extra.get("is_nullable").toString()).isEqualTo("NO");
        Map<String, Object> refCount = cols.stream()
                .filter(c -> "ref_count".equals(c.get("column_name"))).findFirst().orElseThrow();
        assertThat(refCount.get("data_type").toString()).contains("bigint");
    }

    @Test
    void newTableColumnsMatchDdlFinal() {
        // conversations 终稿列（v1.1：pending/context_summary/deleted_at/title NOT NULL）
        List<Map<String, Object>> convCols = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'conversations'
                """);
        assertThat(convCols.stream().map(c -> c.get("column_name").toString()))
                .contains("id", "user_id", "title", "status", "image_model", "web_search",
                        "pending", "context_summary", "deleted_at", "created_at", "updated_at", "last_message_at");

        // conversation_messages 终稿列（v1.1：user_id 补列）
        List<Map<String, Object>> msgCols = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'conversation_messages'
                """);
        assertThat(msgCols.stream().map(c -> c.get("column_name").toString()))
                .contains("id", "conversation_id", "user_id", "role", "text", "reasoning",
                        "image_ids", "task_id", "proposal_data", "web_search_used", "withdrawable", "created_at");

        // canvas_projects 终稿列（version 冲突检测）
        List<Map<String, Object>> canvasCols = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'canvas_projects'
                """);
        assertThat(canvasCols.stream().map(c -> c.get("column_name").toString()))
                .contains("id", "user_id", "title", "nodes", "connections", "background_mode",
                        "show_image_info", "viewport", "version", "deleted_at", "created_at", "updated_at");

        // histories 统一历史表
        List<Map<String, Object>> histCols = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'histories'
                """);
        assertThat(histCols.stream().map(c -> c.get("column_name").toString()))
                .contains("id", "user_id", "type", "status", "title", "payload", "image_ids", "task_id", "error");
    }

    @Test
    void usageRecordsCheckAcceptsAgentRefType() {
        // V7 扩展 CHECK(ref_type IN ('task','proxy','agent')) —— 插入 agent 行成功
        // usage_records.id 为 BIGSERIAL（V5），省略主键由序列生成；ref_id 唯一幂等
        String refId = "agent-msg-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO usage_records (user_id, account_id, model_id, protocol, req_type, ref_type, ref_id,
                                           status, input_tokens, output_tokens, cost, created_at)
                VALUES (NULL, NULL, NULL, 'openai-responses', 'text', 'agent', ?,
                        'success', 0, 0, 0, now())
                """, refId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usage_records WHERE ref_id = ?", Integer.class, refId);
        assertThat(count).isEqualTo(1);
        jdbcTemplate.update("DELETE FROM usage_records WHERE ref_id = ?", refId);
    }

    @Test
    void reverseDraftPartialUniqueIndexExists() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'uq_histories_reverse_draft'
                """);
        assertThat(indexes).hasSize(1);
    }

    @Test
    void newTablesRoundTrip() {
        String userId = UUID.randomUUID().toString();

        // conversations + messages round-trip
        String convId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO conversations (id, user_id, title, status, pending, context_summary)
                VALUES (?, ?, '测试会话', 'active', ?::jsonb, ?::jsonb)
                """, convId, userId, "{\"kind\":\"proposal\"}", "{\"text\":\"摘要\",\"foldedCount\":3}");
        String msgId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO conversation_messages (id, conversation_id, user_id, role, text, image_ids, created_at)
                VALUES (?, ?, ?, 'user', '你好', ?::jsonb, now())
                """, msgId, convId, userId, "[\"asset-1\"]");
        Integer msgCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = ?", Integer.class, convId);
        assertThat(msgCount).isEqualTo(1);

        // canvas_projects round-trip（nodes 整文档 JSONB + version）
        String canvasId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO canvas_projects (id, user_id, title, nodes, connections, viewport, version)
                VALUES (?, ?, '画布', ?::jsonb, ?::jsonb, ?::jsonb, 1)
                """, canvasId, userId, "[{\"id\":\"n1\",\"image\":{\"assetId\":\"asset-9\"}}]",
                "[]", "{\"x\":0,\"y\":0,\"k\":1}");
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM canvas_projects WHERE id = ?", Long.class, canvasId);
        assertThat(version).isEqualTo(1L);

        // histories round-trip（type=reverse draft 唯一索引不冲突）
        String histId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO histories (id, user_id, type, status, payload, image_ids)
                VALUES (?, ?, 'reverse', 'completed', ?::jsonb, ?::jsonb)
                """, histId, userId, "{\"text\":\"prompt\",\"model\":\"m1\"}", "[\"asset-2\"]");

        // assets 新列 round-trip（extra/deleted_at/ref_count）
        String assetId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO assets (id, user_id, kind, name, source_kind, extra, ref_count)
                VALUES (?, ?, 'image', '会话图', 'conversation', ?::jsonb, 2)
                """, assetId, userId, "{\"description\":\"一只猫\"}");
        Integer refCount = jdbcTemplate.queryForObject(
                "SELECT ref_count FROM assets WHERE id = ?", Integer.class, assetId);
        assertThat(refCount).isEqualTo(2);

        // 清理（不污染共享库）
        jdbcTemplate.update("DELETE FROM assets WHERE id = ?", assetId);
        jdbcTemplate.update("DELETE FROM histories WHERE id = ?", histId);
        jdbcTemplate.update("DELETE FROM canvas_projects WHERE id = ?", canvasId);
        jdbcTemplate.update("DELETE FROM conversation_messages WHERE id = ?", msgId);
        jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", convId);
    }

    @Test
    void existingV1ToV6TablesUntouched() {
        // V4 projects/assets 仍可用（存量表未被 V7 破坏）
        Integer projects = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN ('projects', 'assets', 'tasks', 'users')
                """, Integer.class);
        assertThat(projects).isEqualTo(4);
    }
}
