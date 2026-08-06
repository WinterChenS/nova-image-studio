-- ============================================================================
-- WIN-25 (v3.3.0): AI 模型账号统一管理（账号池+负载均衡）+ 用量/费用审计
--                 + RBAC 权限系统 + 登录门禁（Flyway V5：结构）
--
-- 来源：docs/DDL-win25-account-pool-rbac-v1.0.sql（架构终稿，WIN-27 评审通过）
-- 设计文档：docs/ARCH-win25-account-pool-rbac-v1.0.md（Part C / 附录 A）
-- 编号约定：基于已合入 V4（WIN-22，302a811）；若后续合并顺序导致编号冲突，
--           按实际已合入的最大版本号顺延。
--
-- 与 PRD §9.1 草案的差异（终稿）：
--   1) ai_model_pricing 增加 UNIQUE(model_id, currency) —— 每模型每币种一行，
--      避免同模型多行歧义；历史费用已快照在 usage_records.cost，改价不影响历史。
--   2) ai_accounts.status 增加 CHECK 枚举（active|paused|broken|deleted），
--      deleted = 软删除（ADR-28，保 usage_records 外键审计完整）。
--   3) usage_records 增加 CHECK 枚举（ref_type/req_type/status）与
--      account_id/model_id ON DELETE SET NULL（目录删除不破坏审计历史）。
--   4) usage_daily_agg 草案未给 DDL，本终稿补充（P1，明细清理后聚合仍在）。
--   5) 各表补充 NOT NULL/CHECK/索引，与仓库既有迁移风格一致（V2/V4）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 模型目录（全局，取代 per-user models 语义；旧 models 表逻辑冻结 → P2 物理清理）
--    R5：UNIQUE(protocol, model_id) —— 唯一键不含 base_url（账号可覆盖 base_url）。
-- ---------------------------------------------------------------------------
CREATE TABLE ai_models (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type              VARCHAR(8)  NOT NULL CHECK (type IN ('image', 'text')),
    protocol          VARCHAR(32) NOT NULL,               -- google|openai|grok|google-gemini|anthropic-messages|openai-chat-completions|openai-responses
    name              VARCHAR(128) NOT NULL,              -- 展示名
    model_id          VARCHAR(128) NOT NULL,              -- 上游模型名
    base_url          VARCHAR(512) NOT NULL,              -- 默认 Base URL（账号可覆盖）
    capabilities      JSONB NOT NULL DEFAULT '{}',        -- max_ref_images/max_output_size/supports_advanced_params/builtin_preset/note
    builtin_preset_id VARCHAR(64),
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    created_by        UUID REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (protocol, model_id)                -- R5（去 base_url）
);
CREATE INDEX idx_ai_models_type_enabled ON ai_models(type, enabled);

-- ---------------------------------------------------------------------------
-- 2. 账号池（全局；Key AES-GCM 复用 CryptoService；删除 = 软删除 status='deleted'）
--    R1：model_scope JSONB 无 FK 完整性 → 服务层校验存在性 + 目录模型删除联动清理。
-- ---------------------------------------------------------------------------
CREATE TABLE ai_accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(128) NOT NULL,                -- 别名（如「Gemini-主账号」）
    protocol        VARCHAR(32) NOT NULL,
    base_url        VARCHAR(512) NOT NULL,
    api_key_enc     TEXT,                                 -- AES-GCM: v1:iv:tag:cipher（复用 CryptoService）
    model_scope     JSONB NOT NULL DEFAULT '[]',          -- 可服务 ai_models id 列表；[] = 全部（服务层按 protocol 交叉校验，ADR-34）
    status          VARCHAR(16) NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'paused', 'broken', 'deleted')),  -- ADR-28 软删除
    priority        INTEGER NOT NULL DEFAULT 100,         -- 调度权重（预留，H3 均等）
    monthly_cap_cost NUMERIC(14,4),                       -- 月度费用上限（P1 F-31）
    health          JSONB NOT NULL DEFAULT '{}',          -- consecutive_failures/cooldown_until/last_error/last_success_at
    remark          TEXT,
    created_by      UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_accounts_status ON ai_accounts(status);

-- ---------------------------------------------------------------------------
-- 3. 价格表（双口径，B3：per_request_price + price_per_token，费用叠加）
--    终稿差异：UNIQUE(model_id, currency) —— 每模型每币种一行。
-- ---------------------------------------------------------------------------
CREATE TABLE ai_model_pricing (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id            UUID NOT NULL REFERENCES ai_models(id) ON DELETE CASCADE,
    currency            VARCHAR(8) NOT NULL DEFAULT 'CNY',
    per_request_price   NUMERIC(14,6),                    -- 单次调用价格（含并行多图视为一次调用）
    price_per_token     NUMERIC(14,8),                    -- 每 token 单价（统一口径；输入/输出分流 P2）
    effective_from      TIMESTAMPTZ,                      -- 预留：未来价格行
    created_by          UUID REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (model_id, currency)
);

-- ---------------------------------------------------------------------------
-- 4. 用量/费用明细（请求级；R2 幂等 UNIQUE(ref_type, ref_id)；cost 快照落库）
--    cost = 单次价 + tokens × 每token单价（写入时固化，改价不影响历史，A8）
--    账号/目录删除不破坏审计历史：account_id/model_id ON DELETE SET NULL（ADR-28）
-- ---------------------------------------------------------------------------
CREATE TABLE usage_records (
    id            BIGSERIAL PRIMARY KEY,
    user_id       UUID REFERENCES users(id) ON DELETE SET NULL,
    account_id    UUID REFERENCES ai_accounts(id) ON DELETE SET NULL,
    model_id      UUID REFERENCES ai_models(id) ON DELETE SET NULL,
    protocol      VARCHAR(32) NOT NULL,
    req_type      VARCHAR(16) NOT NULL CHECK (req_type IN ('image', 'text')),
    ref_type      VARCHAR(16) NOT NULL CHECK (ref_type IN ('task', 'proxy')),
    ref_id        VARCHAR(64),                            -- taskId / 代理请求 UUID（H5）
    status        VARCHAR(16) NOT NULL CHECK (status IN ('success', 'failed', 'retried')),
    input_tokens  BIGINT,
    output_tokens BIGINT,
    images        INTEGER,
    cost          NUMERIC(14,6),                          -- 快照单价计算：单次价 + tokens × 每token单价
    currency      VARCHAR(8) NOT NULL DEFAULT 'CNY',
    duration_ms   BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (ref_type, ref_id)            -- R2 幂等：一次业务请求一条记录，换账号重试不重复计费
);
CREATE INDEX idx_usage_user    ON usage_records(user_id, created_at DESC);
CREATE INDEX idx_usage_model   ON usage_records(model_id, created_at DESC);
CREATE INDEX idx_usage_account ON usage_records(account_id, created_at DESC);
CREATE INDEX idx_usage_created ON usage_records(created_at DESC);

-- ---------------------------------------------------------------------------
-- 5. 用量日聚合（P1 F-33）：明细清理后聚合仍在（A10）
--    聚合 = 用户×模型×账号×req_type 每日汇总；cost 用明细快照 cost 求和（不重算）。
-- ---------------------------------------------------------------------------
CREATE TABLE usage_daily_agg (
    agg_date        DATE NOT NULL,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    model_id        UUID REFERENCES ai_models(id) ON DELETE SET NULL,
    account_id      UUID REFERENCES ai_accounts(id) ON DELETE SET NULL,
    req_type        VARCHAR(16) NOT NULL CHECK (req_type IN ('image', 'text')),
    request_count   INTEGER NOT NULL DEFAULT 0,
    success_count   INTEGER NOT NULL DEFAULT 0,
    input_tokens    BIGINT NOT NULL DEFAULT 0,
    output_tokens   BIGINT NOT NULL DEFAULT 0,
    cost            NUMERIC(16,6) NOT NULL DEFAULT 0,
    currency        VARCHAR(8) NOT NULL DEFAULT 'CNY',
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agg_date, user_id, model_id, account_id, req_type)
);

-- ---------------------------------------------------------------------------
-- 6. RBAC 四表（需求 3；多角色/自定义角色扩展预留：user_roles 多行、roles.builtin）
-- ---------------------------------------------------------------------------
CREATE TABLE roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(32) NOT NULL UNIQUE,              -- admin|user（内置）
    name        VARCHAR(64) NOT NULL,
    builtin     BOOLEAN NOT NULL DEFAULT TRUE,            -- 内置角色不可删除（A15）
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE permissions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(64) NOT NULL UNIQUE,              -- account.manage / audit.view / ...
    type        VARCHAR(8) NOT NULL CHECK (type IN ('menu', 'button')),
    parent_code VARCHAR(64),                              -- 菜单层级
    label       VARCHAR(64) NOT NULL,
    api_path    VARCHAR(255),                             -- 关联接口前缀（P1 矩阵界面映射 + 防漂移单测）
    sort_order  INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX idx_role_permissions_role ON role_permissions(role_id);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_roles_user ON user_roles(user_id);

-- ---------------------------------------------------------------------------
-- 7. 变更审计日志（P1 F-34/A16）：账号/价格/角色/权限变更记录，与 usage 同保留期
-- ---------------------------------------------------------------------------
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    action      VARCHAR(64) NOT NULL,                     -- account.create / pricing.update / role_permissions.update ...
    target_type VARCHAR(32) NOT NULL,                     -- ai_accounts / ai_model_pricing / role_permissions / user_roles ...
    target_id   VARCHAR(64),
    detail      JSONB NOT NULL DEFAULT '{}',              -- 变更前后摘要（不落 Key/凭据）
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_created ON audit_log(created_at DESC);
CREATE INDEX idx_audit_log_target  ON audit_log(target_type, target_id);
