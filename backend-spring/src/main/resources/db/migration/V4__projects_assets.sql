-- ============================================================================
-- WIN-22 (v3.2.0): 素材按项目维度隔离 + 统一素材管理 + MinIO 对象存储（V4）
--
--   projects — 每用户私有项目（默认项目懒创建、归档、auto_save 自动收藏开关）。
--   users    — 新增 status（active|disabled，禁用登录拒 401）+ last_login_at
--              （用户管理「最近登录」列）。
--   tasks    — 新增 project_id（NULL = 「未分类」虚拟桶，仅历史/迁移数据允许）。
--   assets   — 素材元数据表（取代前端 IndexedDB 主存储；二进制存 MinIO/磁盘，
--              storage_key 为对象 key；text 素材 storage_key NULL）。
--
-- 设计要点（ARCH Part C，评审通过）：
--   1) projects/assets 主键沿用 tasks 的 TEXT + 代码生成 UUID（MyBatis-Plus
--      IdType.INPUT），与 TaskEntity 风格一致。
--   2) project_id 不加外键：语义允许「未分类 NULL」与「删除项目 → 任务置 NULL」，
--      属主校验在 Service 层兜底（显式架构决策，与 PRD 表结构一致）。
--   3) 幂等：Flyway 版本化迁移，ADD COLUMN 仅本次执行一次；存量数据零改写
--      （旧 tasks 行 project_id 保持 NULL，旧 users 行 status 默认 active）。
-- ============================================================================

CREATE TABLE projects (
    id          TEXT PRIMARY KEY,                     -- UUID（代码生成）
    user_id     TEXT NOT NULL,                        -- 属主（users.id 字符串形式）
    name        TEXT NOT NULL,                        -- 必填（1–64）
    description TEXT,
    archived    BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    auto_save   BOOLEAN NOT NULL DEFAULT FALSE,       -- F-8 P1：生成结果自动入库该项目素材
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_projects_user ON projects(user_id, archived, sort_order);

-- users：禁用字段 + 最近登录（Q3）
ALTER TABLE users ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active';  -- active|disabled
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMPTZ;

-- tasks：新增项目归属（旧行 NULL = 未分类，G-4）
ALTER TABLE tasks ADD COLUMN project_id TEXT;

-- assets（取代 IndexedDB 主存储）
CREATE TABLE assets (
    id           TEXT PRIMARY KEY,                  -- UUID（代码生成）
    user_id      TEXT NOT NULL,                     -- 属主
    project_id   TEXT,                              -- NULL = 「未分类」（虚拟桶）
    kind         TEXT NOT NULL,                     -- image | text
    name         TEXT,
    mime_type    TEXT,
    size_bytes   BIGINT,
    width        INTEGER,
    height       INTEGER,
    tags         JSONB NOT NULL DEFAULT '[]',
    note         TEXT,
    source_kind  TEXT NOT NULL,                     -- 9 值枚举（AssetSourceKind）
    source_label TEXT,
    source_ref   TEXT,                              -- taskId / 文件名
    prompt       TEXT,
    storage_key  TEXT,                              -- MinIO object key（text 素材为 NULL）
    hash         TEXT,                              -- SHA-256（去重参考，R-5）
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);
CREATE INDEX idx_assets_project ON assets(user_id, project_id, source_kind);
CREATE INDEX idx_assets_created  ON assets(user_id, project_id, created_at DESC);
