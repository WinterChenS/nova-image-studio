#!/usr/bin/env bash
# T2.8 (WIN-12): one-time SQLite → PostgreSQL task migration (idempotent).
#
# Usage:
#   ./migrate-sqlite-to-pg.sh                # uses backend/nova-tasks.sqlite + .env DB_*
#   ./migrate-sqlite-to-pg.sh --sqlite /path/to/nova-tasks.sqlite
#
# Reads the legacy Node backend's nova-tasks.sqlite and upserts every row into
# the Spring backend's PostgreSQL tasks/task_items (ON CONFLICT DO NOTHING —
# safe to re-run; only missing rows are inserted). Legacy 'queued' status is
# normalized to '排队中'; user_id stays NULL (legacy/system ownership, Q1).
#
# Prereqs: JDK 21 + Maven (resolves sqlite-jdbc/postgresql from the local repo
# or the configured mirror), backend-spring/.env with DB_* credentials.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-21.0.3+9}"
export PATH="$JAVA_HOME/bin:/c/Program Files/apache-maven-3.9.6/bin:$PATH"

if [ ! -f .env ]; then
  echo "ERROR: backend-spring/.env 不存在 — 请先复制 .env.example 为 .env 并填入 DB 凭据" >&2
  exit 2
fi
# shellcheck disable=SC2046
export $(grep -E '^[A-Z]' .env | xargs)

echo "== 编译并解析依赖 classpath =="
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/migrate-cp.txt

CP="target/classes;$(cat target/migrate-cp.txt)"
"$JAVA_HOME/bin/java" -cp "$CP" com.nova.studio.migration.SqliteToPgMigrator "$@"
