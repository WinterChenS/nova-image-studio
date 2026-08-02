-- ============================================================================
-- M0 skeleton spike (WIN-10 / T0.1): minimal schema to prove Flyway migration
-- + PostgreSQL connectivity. The real product DDL (users/models/settings/tasks/
-- task_items/prompts/blacklist, ARCH Part D) is delivered in milestone M1.
-- ============================================================================

CREATE TABLE spike_probe (
    id         BIGSERIAL PRIMARY KEY,
    note       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO spike_probe (note) VALUES ('M0 spike flyway ok');
