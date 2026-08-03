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
npm run test:ab-diff
AB_EXIT=$?
echo "ab-diff exit=$AB_EXIT"
exit $AB_EXIT
