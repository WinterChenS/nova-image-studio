-- ============================================================================
-- T3.1 (WIN-13 / M3): prompts/blacklist DB-ization (F-14, ARCH H5).
--
--   prompts   — local QuickPromptDialog templates (shape parity with the
--               legacy backend/prompts.json: [{title, content, type}]).
--               type: 1 = text-to-image, 2 = image-to-image. `enabled` lets an
--               admin hide entries without deleting (public GET serves all
--               rows to keep the #12 checklist length parity with the file).
--   blacklist — sensitive keyword list (parity with backend/blacklist.json
--               {keywords: [...]}). keyword is UNIQUE so seed + admin adds are
--               idempotent.
--
-- First-startup seed (table empty + file present) and file fallback (DB read
-- failure) live in GalleryDataService — this migration only defines the schema.
-- ============================================================================

CREATE TABLE prompts (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title      VARCHAR(255) NOT NULL,
    content    TEXT NOT NULL,
    type       SMALLINT NOT NULL DEFAULT 1,            -- 1=文生图, 2=图生图
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_prompts_enabled ON prompts(enabled);

CREATE TABLE blacklist (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keyword    VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
