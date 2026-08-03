-- ============================================================================
-- M1 (WIN-11 / T1.1): task storage on PostgreSQL. Port of the Node backend's
-- SQLite schema (backend/server.js initDatabase) with the user-confirmed
-- Q1 decision applied: tasks.user_id is nullable (NULL = system / migrated
-- legacy ownership; task creation requires login from M2 onward, M1 keeps the
-- same unauthenticated boundary as the Node backend for frontend-zero-change
-- connectivity).
--
-- id is TEXT to stay byte-compatible with the Node backend (randomUUID strings).
-- status uses the Node backend's exact values: '排队中' (queued) / 'processing'
-- / 'completed' / 'failed'; 'expired' is derived on read when expires_at has
-- passed. 'queued' (legacy) is normalized to '排队中' at startup.
-- ============================================================================

CREATE TABLE tasks (
    id           TEXT        PRIMARY KEY,
    user_id      TEXT,                       -- NULL = 系统/迁移遗留（Q1）
    status       VARCHAR(16) NOT NULL,
    mode         VARCHAR(32) NOT NULL,
    request_json JSONB       NOT NULL,
    result_json  JSONB,
    error        TEXT,
    warning      TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ
);

CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_expires_at ON tasks(expires_at);
CREATE INDEX idx_tasks_user_status ON tasks(user_id, status);

CREATE TABLE task_items (
    task_id      TEXT        NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    item_index   INTEGER     NOT NULL,
    status       VARCHAR(16) NOT NULL,
    image_data   TEXT,
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (task_id, item_index)
);

CREATE INDEX idx_task_items_task_id ON task_items(task_id);
