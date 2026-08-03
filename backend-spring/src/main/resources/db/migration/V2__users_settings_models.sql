-- ============================================================================
-- M2 (WIN-12 / T2.1+T2.2): user system + settings/models tables.
--
--   users    — register/login identity (bcrypt password hash, JWT auth).
--   models   — per-user AI model configs (image/text). api_key_enc holds the
--              AES-GCM ciphertext (`v1:iv:tag:cipher`, base64) — never plaintext.
--   settings — per-user business settings package (form defaults, limit.*,
--              registry.defaults.*, agent.*, gallery.*). value is JSONB so a
--              single row carries arbitrarily nested default objects.
--
-- tasks.user_id is promoted TEXT → UUID to match users(id) with a real FK.
-- M1 never wrote user_id (all rows NULL = system/legacy), so the cast is
-- lossless; environments with non-UUID legacy values fail loudly (correct).
-- ============================================================================

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,          -- bcrypt
    role          VARCHAR(16) NOT NULL DEFAULT 'user',  -- user|admin
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE models (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type               VARCHAR(8) NOT NULL,               -- image|text
    protocol           VARCHAR(32) NOT NULL,              -- google|openai|grok|google-gemini|anthropic-messages|openai-chat-completions|openai-responses
    name               VARCHAR(128) NOT NULL,
    model_id           VARCHAR(128) NOT NULL,             -- upstream model name
    base_url           VARCHAR(512) NOT NULL,
    api_key_enc        TEXT,                              -- AES-GCM: v1:iv:tag:cipher (base64)
    capabilities       JSONB NOT NULL DEFAULT '{}',       -- max_ref_images / max_output_size / supports_advanced_params / builtin_preset / note
    builtin_preset_id  VARCHAR(64),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, type, name)
);
CREATE INDEX idx_models_user ON models(user_id);

CREATE TABLE settings (
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key          VARCHAR(128) NOT NULL,
    value        JSONB NOT NULL,
    value_type   VARCHAR(16) NOT NULL DEFAULT 'json',  -- json|string|number|bool
    description  TEXT,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key)
);

-- tasks.user_id: TEXT → UUID (all M1 rows are NULL; NULL keeps meaning
-- "system / migrated legacy ownership" per Q1).
ALTER TABLE tasks ALTER COLUMN user_id TYPE uuid USING NULLIF(user_id, '')::uuid;
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;
