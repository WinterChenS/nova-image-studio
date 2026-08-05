#!/usr/bin/env bash
# WIN-14: run the full backend test suite (incl. DB/REDIS-gated integration tests).
# Loads real credentials from backend-spring/.env (git-ignored) and runs `mvn test`.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-21.0.3+9}"
export PATH="$JAVA_HOME/bin:/c/Program Files/apache-maven-3.9.6/bin:$PATH"
# WIN-14 review #2: fail loudly when the real .env is missing (silent empty
# exports made the suite pass with dummy creds or error confusingly later).
if [ ! -f .env ]; then
  echo "ERROR: backend-spring/.env 不存在 — 请先复制 .env.example 为 .env 并填入真实的 DB/Redis 凭据" >&2
  echo "       cp .env.example .env" >&2
  exit 2
fi
# shellcheck disable=SC2046
export $(grep -E '^[A-Z]' .env | xargs)
echo "== DB_HOST=$DB_HOST REDIS_HOST=$REDIS_HOST =="
mvn test "$@"
