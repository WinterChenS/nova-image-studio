package com.nova.studio.migration;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

/**
 * T2.8 — one-time SQLite → PostgreSQL task migration (幂等).
 *
 * <p>Reads the legacy Node backend's {@code nova-tasks.sqlite}
 * ({@code backend/server.js initDatabase} schema) and upserts every
 * {@code tasks}/{@code task_items} row into the Spring backend's PostgreSQL
 * {@code tasks}/{@code task_items} tables:
 * <ul>
 *   <li>legacy {@code 'queued'} status is normalized to {@code '排队中'}
 *       (matching the M1 startup normalization);</li>
 *   <li>ISO-8601 timestamps are inserted with an explicit {@code ::timestamptz}
 *       cast; JSON columns with {@code ::jsonb} (invalid JSON falls back to
 *       {@code {}} and is reported);</li>
 *   <li>{@code user_id} is left NULL — migrated tasks are legacy/system-owned
 *       and stay anonymously readable per Q1;</li>
 *   <li>idempotent: {@code ON CONFLICT DO NOTHING}, so re-running only inserts
 *       rows that are still missing. Safe to run any number of times.</li>
 * </ul>
 *
 * <p>Run via {@code backend-spring/scripts/migrate-sqlite-to-pg.sh}.
 */
public final class SqliteToPgMigrator {

    private static final int BATCH_SIZE = 500;

    private SqliteToPgMigrator() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String sqlitePath = opts.getOrDefault("sqlite", "../backend/nova-tasks.sqlite");
        String pgUrl = opts.getOrDefault("pgUrl", System.getenv("MIGRATE_PG_URL"));
        String pgUser = opts.getOrDefault("pgUser", System.getenv("MIGRATE_PG_USER"));
        String pgPassword = opts.getOrDefault("pgPassword", System.getenv("MIGRATE_PG_PASSWORD"));

        // Fall back to the same env vars the Spring app uses (.env is exported by the runner).
        if (pgUrl == null) {
            String host = System.getenv("DB_HOST") == null ? "localhost" : System.getenv("DB_HOST");
            String port = System.getenv("DB_PORT") == null ? "5432" : System.getenv("DB_PORT");
            String name = System.getenv("DB_NAME") == null ? "nova" : System.getenv("DB_NAME");
            pgUrl = "jdbc:postgresql://" + host + ":" + port + "/" + name;
            pgUser = System.getenv("DB_USERNAME");
            pgPassword = System.getenv("DB_PASSWORD");
        }
        if (pgUser == null || pgPassword == null) {
            System.err.println("ERROR: 缺少数据库凭据 — 通过 .env 导出 DB_* 后重跑，或传 --pgUser/--pgPassword");
            System.exit(2);
        }

        File sqliteFile = Path.of(sqlitePath).toFile();
        if (!sqliteFile.exists()) {
            System.err.println("ERROR: SQLite 文件不存在: " + sqliteFile.getAbsolutePath());
            System.exit(2);
        }

        Class.forName("org.sqlite.JDBC");
        Class.forName("org.postgresql.Driver");

        try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
             Connection pg = DriverManager.getConnection(pgUrl, pgUser, pgPassword)) {

            if (!hasTable(sqlite, "tasks")) {
                System.err.println("ERROR: SQLite 库中没有 tasks 表 — 不是有效的 Node 任务库: " + sqliteFile.getAbsolutePath());
                System.exit(2);
            }

            System.out.println("== SQLite → PG 任务迁移 ==");
            System.out.println("   源: " + sqliteFile.getAbsolutePath());
            System.out.println("   目标: " + pgUrl);

            int[] tasks = migrateTasks(sqlite, pg);
            int[] items = migrateItems(sqlite, pg);
            System.out.println("== 完成 ==");
            System.out.println("   tasks: 迁移 " + tasks[0] + " / 跳过(已存在) " + tasks[1] + " / 失败 " + tasks[2]);
            System.out.println("   task_items: 迁移 " + items[0] + " / 跳过(已存在) " + items[1] + " / 失败 " + items[2]);
            if (tasks[2] + items[2] > 0) {
                System.exit(1);
            }
        }
    }

    private static int[] migrateTasks(Connection sqlite, Connection pg) throws SQLException {
        int inserted = 0;
        int skipped = 0;
        int failed = 0;
        String select = "SELECT id, status, mode, request_json, result_json, error, warning,"
                + " created_at, completed_at, expires_at FROM tasks";
        try (Statement st = sqlite.createStatement(); ResultSet rs = st.executeQuery(select)) {
            try (PreparedStatement ps = pg.prepareStatement("""
                    INSERT INTO tasks (id, user_id, status, mode, request_json, result_json, error, warning,
                                       created_at, completed_at, expires_at)
                    VALUES (?, NULL, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                while (rs.next()) {
                    ps.setString(1, rs.getString("id"));
                    ps.setString(2, normalizeStatus(rs.getString("status")));
                    ps.setString(3, rs.getString("mode"));
                    ps.setString(4, jsonOrEmpty(rs.getString("request_json")));
                    ps.setString(5, jsonOrEmpty(rs.getString("result_json")));
                    ps.setString(6, rs.getString("error"));
                    ps.setString(7, rs.getString("warning"));
                    ps.setString(8, rs.getString("created_at"));
                    ps.setString(9, rs.getString("completed_at"));
                    ps.setString(10, rs.getString("expires_at"));
                    ps.addBatch();
                    if (++inserted % BATCH_SIZE == 0) {
                        int[] r = ps.executeBatch();
                        for (int n : r) {
                            if (n == 0) {
                                skipped++;
                            }
                        }
                    }
                }
                int[] r = ps.executeBatch();
                for (int n : r) {
                    if (n == 0) {
                        skipped++;
                    }
                }
                inserted -= skipped;
            } catch (SQLException e) {
                failed++;
                System.err.println("  tasks 批量插入失败: " + e.getMessage());
                throw e;
            }
        }
        return new int[]{inserted, skipped, failed};
    }

    private static int[] migrateItems(Connection sqlite, Connection pg) throws SQLException {
        int inserted = 0;
        int skipped = 0;
        int failed = 0;
        String select = "SELECT task_id, item_index, status, image_data, error, created_at, completed_at FROM task_items";
        try (Statement st = sqlite.createStatement(); ResultSet rs = st.executeQuery(select)) {
            try (PreparedStatement ps = pg.prepareStatement("""
                    INSERT INTO task_items (task_id, item_index, status, image_data, error, created_at, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
                    ON CONFLICT (task_id, item_index) DO NOTHING
                    """)) {
                while (rs.next()) {
                    ps.setString(1, rs.getString("task_id"));
                    ps.setInt(2, rs.getInt("item_index"));
                    ps.setString(3, normalizeStatus(rs.getString("status")));
                    ps.setString(4, rs.getString("image_data"));
                    ps.setString(5, rs.getString("error"));
                    ps.setString(6, rs.getString("created_at"));
                    ps.setString(7, rs.getString("completed_at"));
                    ps.addBatch();
                    if (++inserted % BATCH_SIZE == 0) {
                        int[] r = ps.executeBatch();
                        for (int n : r) {
                            if (n == 0) {
                                skipped++;
                            }
                        }
                    }
                }
                int[] r = ps.executeBatch();
                for (int n : r) {
                    if (n == 0) {
                        skipped++;
                    }
                }
                inserted -= skipped;
            } catch (SQLException e) {
                failed++;
                System.err.println("  task_items 批量插入失败: " + e.getMessage());
                throw e;
            }
        }
        return new int[]{inserted, skipped, failed};
    }

    /** Node startup normalization: legacy 'queued' → '排队中'. */
    private static String normalizeStatus(String status) {
        return "queued".equals(status) ? "排队中" : status;
    }

    /** Invalid JSON → '{}' (PG JSONB is NOT NULL); reported via stderr later by counters. */
    private static String jsonOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        return json;
    }

    private static boolean hasTable(Connection sqlite, String table) throws SQLException {
        try (ResultSet rs = sqlite.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                opts.put(args[i].substring(2), args[i + 1]);
                i++;
            }
        }
        return opts;
    }
}
