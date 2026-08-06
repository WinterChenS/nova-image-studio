#!/usr/bin/env bash
# WIN-14 verification: run the A/B diff (T1.11) end-to-end — Node backend (3000)
# + Spring backend (8080) + mock upstream, same frontend/out. All in one blocking
# call: start both, wait for readiness, run ab-diff.mjs, then stop both.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../.."   # repo root
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-21.0.3+9}"
export PATH="$JAVA_HOME/bin:/c/Program Files/apache-maven-3.9.6/bin:$PATH"

# ---- load real DB/Redis creds for the Spring backend (git-ignored .env) ----
# WIN-14 review #2: fail loudly when .env is missing (silent empty exports would
# make the Spring side boot with dummy creds and fail deep inside the A/B run).
if [ ! -f backend-spring/.env ]; then
  echo "ERROR: backend-spring/.env 不存在 — 请先复制 .env.example 为 .env 并填入真实凭据" >&2
  echo "       cp backend-spring/.env.example backend-spring/.env" >&2
  exit 2
fi
# shellcheck disable=SC2046
export $(grep -E '^[A-Z]' backend-spring/.env | xargs)

cleanup() {
  kill "$NODE_PID" "$SPRING_PID" 2>/dev/null
  wait "$NODE_PID" 2>/dev/null
  wait "$SPRING_PID" 2>/dev/null
}
trap cleanup EXIT

echo "== starting Node backend (3000) =="
(cd backend && PORT=3000 NODE_ENV=production node server.js) > /tmp/ab-node.log 2>&1 &
NODE_PID=$!

echo "== starting Spring backend (8080) =="
(cd backend-spring && "$JAVA_HOME/bin/java" -jar target/nova-studio-backend-0.1.0-SNAPSHOT.jar) > /tmp/ab-spring.log 2>&1 &
SPRING_PID=$!

# ---- wait for readiness (health endpoints) ----
node_ok=0; spring_ok=0
for i in $(seq 1 60); do
  [ "$node_ok" = 0 ] && curl -sf -o /dev/null http://localhost:3000/api/nova/config && node_ok=1 && echo "node ready (${i}s)"
  [ "$spring_ok" = 0 ] && curl -sf -o /dev/null http://localhost:8080/api/nova/config && spring_ok=1 && echo "spring ready (${i}s)"
  [ "$node_ok" = 1 ] && [ "$spring_ok" = 1 ] && break
  sleep 1
done
echo "node_ok=$node_ok spring_ok=$spring_ok"

if [ "$node_ok" != 1 ] || [ "$spring_ok" != 1 ]; then
  echo "===== node log tail ====="; tail -20 /tmp/ab-node.log
  echo "===== spring log tail ====="; tail -30 /tmp/ab-spring.log
  exit 1
fi

echo "== running ab-diff via npm script (test:ab-diff) =="
# WIN-28: 先播种目录模型 + 账号（Spring 账号池探针需要），再跑 A/B
if command -v "$JAVA_HOME/bin/java" >/dev/null 2>&1; then :; fi
echo "== seeding ab-diff catalog + account =="
(cd backend-spring && mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/abdiff-cp.txt)
ABDIFF_IDS="$("$JAVA_HOME/bin/java" -cp "backend-spring/target/classes;$(cat backend-spring/target/abdiff-cp.txt)" com.nova.studio.integration.AbDiffSeed)"
export ABDIFF_CATALOG_MODEL_ID="${ABDIFF_IDS%%,*}"
export ABDIFF_TEXT_CATALOG_MODEL_ID="${ABDIFF_IDS##*,}"
echo "ABDIFF_CATALOG_MODEL_ID=$ABDIFF_CATALOG_MODEL_ID ABDIFF_TEXT_CATALOG_MODEL_ID=$ABDIFF_TEXT_CATALOG_MODEL_ID"
npm run test:ab-diff
AB_EXIT=$?
echo "ab-diff exit=$AB_EXIT"
exit $AB_EXIT
