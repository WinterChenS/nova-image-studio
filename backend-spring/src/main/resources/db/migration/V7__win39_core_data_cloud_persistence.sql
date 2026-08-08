-- ============================================================================
-- WIN-38/WIN-39 (v3.4.0 目标): 核心创作数据云端持久化 + Agent 模式后端化（V7）
--
-- 来源：docs/DDL-win39-core-data-cloud-persistence-v1.0.sql（结构终稿 v1.1，
--       协调者评审 `004a3891` + Owner 拍板 `f300a682`）
-- 设计文档：docs/ARCH-win39-core-data-cloud-persistence-v1.0.md（Part F，文档内版本 v1.1）
--
-- v1.1 修订：
--   * 新表 user_id 统一 TEXT（与 assets/projects/tasks 的 V4 约定对齐）
--   * conversation_messages 补 user_id TEXT（「所有新表 user_id」原则）
--   * conversations 增 context_summary JSONB（自动上下文压缩摘要，ADR-44）
--
-- 与 PRD §7.1 草案的差异（终稿，理由见 ARCH Part F.2）：
--   1) conversation_images 表删除 → 图片字节与登记统一并入 assets
--      （source_kind='conversation'，目录元数据入 assets.extra）。
--   2) conversations 增加 pending JSONB / context_summary JSONB / deleted_at；
--      title NOT NULL（C1 列表必填）。
--   3) reverse_prompt_records + gif_jobs 两表合并为 histories 统一历史表。
--   4) canvas_projects 节点图片引用语义改为 assets.id 引用（无新表）。
--   5) assets 增加 extra JSONB / deleted_at / ref_count。
--   6) usage_records.ref_type CHECK 扩展 'agent'（AC-7）。
--   7) 配额默认值不入库（settings 表 + SettingsService Java 常量，limit.* 已白名单）。
--
-- 幂等：Flyway 版本化迁移（V7 仅执行一次）；DROP/ADD CONSTRAINT 带 IF EXISTS/
-- 命名约束，DDL 与终稿逐字一致，存量 V1–V6 数据零改写。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. assets 扩展（统一素材模型，C4/总原则）——无 source_kind CHECK 约束（服务层校验），
--    新增类型仅需 Java 侧扩展 SOURCE_KINDS 集合（'canvas'/'conversation'）。
-- ---------------------------------------------------------------------------
ALTER TABLE assets ADD COLUMN extra      JSONB NOT NULL DEFAULT '{}';
ALTER TABLE assets ADD COLUMN deleted_at TIMESTAMPTZ;              -- 软删回收站（C8）
ALTER TABLE assets ADD COLUMN ref_count  BIGINT NOT NULL DEFAULT 0; -- 画布/会话引用计数（引用保护）
CREATE INDEX idx_assets_source_ref ON assets(user_id, source_kind, source_ref);
CREATE INDEX idx_assets_deleted    ON assets(user_id, deleted_at);

-- ---------------------------------------------------------------------------
-- 2. conversations（F1/F2 多会话实体，C1）
--    pending 一次仅承载一个进行中状态（pendingProposal 或 pendingGeneration）。
-- ---------------------------------------------------------------------------
CREATE TABLE conversations (
    id              TEXT PRIMARY KEY,                 -- UUID（代码生成）
    user_id         TEXT NOT NULL,                    -- 属主（字符串形式 UUID，与 assets 对齐）
    title           TEXT NOT NULL DEFAULT '未命名会话', -- C1：会话列表必填
    status          VARCHAR(16) NOT NULL DEFAULT 'active',  -- active|archived|deleted
    image_model     TEXT,                             -- 会话内当前图像模型（原 meta.imageModel）
    web_search      BOOLEAN NOT NULL DEFAULT FALSE,   -- 会话内联网搜索开关
    pending         JSONB,                            -- 进行中恢复态：{kind:'proposal'|'generation', ...}
    context_summary JSONB,                            -- 自动上下文压缩摘要（ADR-44）：{text,model,foldedBeforeMessageId,foldedCount,foldedAt}
    deleted_at      TIMESTAMPTZ,                      -- status=deleted 时写入（回收站）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_message_at TIMESTAMPTZ
);
CREATE INDEX idx_conversations_user ON conversations(user_id, status, last_message_at DESC);

-- ---------------------------------------------------------------------------
-- 3. conversation_messages（F1/F2）
--    差异：image_ids 引用 assets.id（统一素材），不再独立 conversation_images 表。
-- ---------------------------------------------------------------------------
CREATE TABLE conversation_messages (
    id              TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    user_id         TEXT NOT NULL,                    -- 属主冗余（与 conversations 一致；用户级清理/审计，v1.1 修订）
    role            VARCHAR(16) NOT NULL,             -- user|assistant|system-note|context-divider
    text            TEXT NOT NULL,
    reasoning       TEXT,                             -- 推理摘要（仅展示）
    image_ids       JSONB NOT NULL DEFAULT '[]',      -- assets.id 列表（对应 Agent 图片目录）
    task_id         TEXT,                             -- 关联生图任务（assistant 消息）
    proposal_data   JSONB,                            -- 已确认提案（重编辑用）
    web_search_used BOOLEAN,
    withdrawable    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cmsg_conversation ON conversation_messages(conversation_id, created_at);

-- ---------------------------------------------------------------------------
-- 4. canvas_projects（F3；结构文档化 + 素材引用化，ADR-35）
--    nodes/connections/viewport 整文档 JSONB；节点图片引用 assets.id。
-- ---------------------------------------------------------------------------
CREATE TABLE canvas_projects (
    id               TEXT PRIMARY KEY,
    user_id          TEXT NOT NULL,                   -- 属主（字符串形式 UUID，与 assets 对齐）
    title            TEXT NOT NULL,
    nodes            JSONB NOT NULL DEFAULT '[]',     -- CanvasNodeData[]（image 引用 = assetId）
    connections      JSONB NOT NULL DEFAULT '[]',     -- CanvasConnection[]
    background_mode  VARCHAR(16) NOT NULL DEFAULT 'lines',
    show_image_info  BOOLEAN NOT NULL DEFAULT FALSE,
    viewport         JSONB NOT NULL DEFAULT '{"x":0,"y":0,"k":1}',
    version          BIGINT NOT NULL DEFAULT 1,       -- 冲突检测（P2 启用）
    deleted_at       TIMESTAMPTZ,                     -- 软删回收站（C8）
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_canvas_user ON canvas_projects(user_id, deleted_at, updated_at DESC);

-- ---------------------------------------------------------------------------
-- 5. histories（统一历史管理模型，ADR-39；C5/C6/总原则）
--    type: reverse | gif
--    reverse.status: draft|completed   （draft 每用户至多一条）
--    gif.status: idle|generating_grid|review_grid|generating_gif|done|failed
--    image_ids 引用 assets.id（输入图/网格图/成品）；task_id = GIF 网格任务。
-- ---------------------------------------------------------------------------
CREATE TABLE histories (
    id           TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL,                       -- 属主（字符串形式 UUID，与 assets 对齐）
    type         VARCHAR(24) NOT NULL,                -- reverse | gif
    status       VARCHAR(24) NOT NULL DEFAULT 'completed',
    title        TEXT,                                -- 列表展示用（可空）
    payload      JSONB NOT NULL DEFAULT '{}',         -- reverse:{text,model,mode} gif:{prompt,model,params,frame...}
    image_ids    JSONB NOT NULL DEFAULT '[]',         -- assets.id 引用
    task_id      TEXT,                                -- gif 网格生成任务
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_histories_user_type ON histories(user_id, type, created_at DESC);
CREATE UNIQUE INDEX uq_histories_reverse_draft ON histories(user_id) WHERE type = 'reverse' AND status = 'draft';

-- ---------------------------------------------------------------------------
-- 6. prompt_gallery_items（F6 提示广场入库，C7；全局数据，无 user_id）
--    与 PRD 草案一致：id = source-uniqueKey，同步幂等 upsert。
-- ---------------------------------------------------------------------------
CREATE TABLE prompt_gallery_items (
    id           TEXT PRIMARY KEY,                    -- uniqueKey（source-源内 id）
    source       VARCHAR(64) NOT NULL,
    source_url   TEXT,
    title        TEXT NOT NULL,
    content      TEXT NOT NULL,
    images       JSONB NOT NULL DEFAULT '[]',         -- 图片 URL 列表（外链）
    tags         JSONB NOT NULL DEFAULT '[]',
    category     TEXT,
    contributor  TEXT,
    notes        TEXT,
    raw_snapshot JSONB,                               -- 源原始数据快照（排障/重解析）
    synced_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pg_source   ON prompt_gallery_items(source);
CREATE INDEX idx_pg_category ON prompt_gallery_items(category);

-- ---------------------------------------------------------------------------
-- 7. usage_records.ref_type 扩展 'agent'（AC-7：Agent 文本用量与生图计量并列）
--    Flyway 命名约束为 usage_records_ref_type_check（V5 内联 CHECK 自动命名）。
-- ---------------------------------------------------------------------------
ALTER TABLE usage_records DROP CONSTRAINT IF EXISTS usage_records_ref_type_check;
ALTER TABLE usage_records ADD CONSTRAINT usage_records_ref_type_check
    CHECK (ref_type IN ('task', 'proxy', 'agent'));

-- 配额/保留默认值：不入库（沿用 SettingsService Java 常量 + settings 表 limit.* 覆盖），
-- 新增键约定见 ARCH Part F.4 / Part J（limit.agentConversationCap=100、
-- limit.agentMessageCapPerConversation=500、agent.contextCompressThreshold=60、
-- agent.contextKeepRecent=20、limit.canvasProjectCap=100、limit.historyCapReverse=500、
-- limit.historyCapGif=100、limit.gifResultRetentionDays=180、limit.archivedPurgeDays=180、
-- limit.canvasRecycleDays=30、limit.assetRecycleDays=30）。
-- ============================================================================
