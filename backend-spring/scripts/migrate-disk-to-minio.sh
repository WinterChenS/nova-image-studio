#!/usr/bin/env bash
# WIN-22 (F-36): one-time disk → MinIO migration (idempotent, re-runnable).
#
# Usage:
#   ./migrate-disk-to-minio.sh              # uses backend-spring/.env MINIO_* + NOVA_IMAGE_DIR
#   ./migrate-disk-to-minio.sh --dir /path/to/nova-images
#
# Scans NOVA_IMAGE_DIR for flat {taskId}-{index}-{sub}.{ext} files and uploads
# each as tasks/{taskId}/{index}-{sub}.{ext} into MINIO_BUCKET. Idempotent:
# already-present objects are skipped (statObject), so re-running only uploads
# the missing remainder. The disk directory is never modified — rollback is
# simply keeping the files (the app falls back to disk when MinIO is down).
#
# Prereqs: JDK 21 + Maven, backend-spring/.env with MINIO_* (and DB_* not required).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-21.0.3+9}"
export PATH="$JAVA_HOME/bin:/c/Program Files/apache-maven-3.9.6/bin:$PATH"

if [ ! -f .env ]; then
  echo "ERROR: backend-spring/.env 不存在 — 请先复制 .env.example 为 .env 并填入 MINIO 凭据" >&2
  exit 2
fi
# shellcheck disable=SC2046
export $(grep -E '^[A-Z]' .env | xargs)

if [ "${MINIO_ENABLED:-true}" = "false" ]; then
  echo "ERROR: MINIO_ENABLED=false，迁移无意义（当前强制磁盘模式）" >&2
  exit 2
fi

echo "== 编译并解析依赖 classpath =="
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/migrate-cp.txt

CP="target/classes;$(cat target/migrate-cp.txt)"
"$JAVA_HOME/bin/java" -cp "$CP" com.nova.studio.migration.DiskToMinioMigrator "$@"
