package com.nova.studio.integration;

import com.nova.studio.settings.CryptoService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

/**
 * WIN-28 A/B 回归种子（独立 main，非 Spring Bean）：在 PostgreSQL 中插入
 * <ul>
 *   <li>一条 openai <b>图片</b>目录模型 + active 账号（任务探针）；</li>
 *   <li>一条 openai-chat-completions <b>文本</b>目录模型 + active 账号（proxy 探针）；</li>
 * </ul>
 * base_url 指向 A/B 脚本的 mock 上游，Key 用 {@link CryptoService}（读取
 * NOVA_SETTINGS_SECRET_KEY）加密为合法密文。幂等（按名称 upsert）。
 *
 * <p>用法（run-ab-diff.sh / ab-diff.yml 在 Spring 就绪后调用）：
 * <pre>
 *   mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/abdiff-cp.txt
 *   java -cp "target/classes;$(cat target/abdiff-cp.txt)" \
 *        com.nova.studio.integration.AbDiffSeed [--mock-port 18099]
 * </pre>
 * 输出：{imageModelId},{textModelId}（供 ab-diff.mjs 经 ABDIFF_CATALOG_MODEL_ID /
 * ABDIFF_TEXT_CATALOG_MODEL_ID 读取）。
 */
public class AbDiffSeed {

    public static void main(String[] args) throws Exception {
        int mockPort = 18099;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--mock-port".equals(args[i])) {
                mockPort = Integer.parseInt(args[i + 1]);
            }
        }
        String host = env("DB_HOST", "localhost");
        String port = env("DB_PORT", "5432");
        String db = env("DB_NAME", "nova");
        String user = env("DB_USERNAME", "postgres");
        String password = env("DB_PASSWORD", "");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        String keyEnc = new CryptoService(System.getenv("NOVA_SETTINGS_SECRET_KEY")).encrypt("sk-ab-diff-key");
        String baseUrl = "http://localhost:" + mockPort;

        UUID imageModelId = UUID.randomUUID();
        UUID textModelId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            // 幂等：先清掉同名旧种子（历史失败的运行可能残留）
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM usage_records WHERE account_id IN (SELECT id FROM ai_accounts WHERE name IN ('ab-diff-account', 'ab-diff-text-account'))");
                st.executeUpdate("DELETE FROM ai_model_pricing WHERE model_id IN (SELECT id FROM ai_models WHERE name IN ('ab-diff-model', 'ab-diff-text-model'))");
                st.executeUpdate("DELETE FROM ai_accounts WHERE name IN ('ab-diff-account', 'ab-diff-text-account')");
                st.executeUpdate("DELETE FROM ai_models WHERE name IN ('ab-diff-model', 'ab-diff-text-model')");
            }
            insertModel(conn, imageModelId, "image", "openai", "ab-diff-model", "gpt-image-1", baseUrl);
            insertModel(conn, textModelId, "text", "openai-chat-completions", "ab-diff-text-model", "gpt-4o", baseUrl);
            insertAccount(conn, "ab-diff-account", "openai", baseUrl, keyEnc);
            insertAccount(conn, "ab-diff-text-account", "openai-chat-completions", baseUrl, keyEnc);
        }
        System.out.println(imageModelId + "," + textModelId);
    }

    private static void insertModel(Connection conn, UUID id, String type, String protocol,
                                    String name, String modelId, String baseUrl) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO ai_models (id, type, protocol, name, model_id, base_url, capabilities, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, '{}', TRUE, now(), now())
                """)) {
            ps.setObject(1, id);
            ps.setString(2, type);
            ps.setString(3, protocol);
            ps.setString(4, name);
            ps.setString(5, modelId);
            ps.setString(6, baseUrl);
            ps.executeUpdate();
        }
    }

    private static void insertAccount(Connection conn, String name, String protocol,
                                      String baseUrl, String keyEnc) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO ai_accounts (id, name, protocol, base_url, api_key_enc, model_scope, status, priority, health, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, '[]', 'active', 100, '{}', now(), now())
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, name);
            ps.setString(3, protocol);
            ps.setString(4, baseUrl);
            ps.setString(5, keyEnc);
            ps.executeUpdate();
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
