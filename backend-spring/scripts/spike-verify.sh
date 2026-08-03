#!/usr/bin/env bash
# M0 spike 连通性验证脚本（WIN-10 / T0.1+T0.4）
# 前置：backend-spring/.env 已配置真实连接；JDK21 + Maven 在 PATH 或 JAVA_HOME 指向 JDK21。
# 用法：bash scripts/spike-verify.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SPRING_DIR="$ROOT"
FRONTEND_DIR="$ROOT/../frontend"
PORT="${PORT:-8080}"
LOG="$(mktemp -t spike-app.XXXXXX.log)"
APP_PID=""

cleanup() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    echo "[spike] stopping spring app (pid $APP_PID)"
    kill "$APP_PID" 2>/dev/null || true
  fi
  rm -f "$LOG"
}
trap cleanup EXIT

echo "[spike] 1/4 building backend-spring"
(cd "$SPRING_DIR" && mvn -q -B -DskipTests package)

echo "[spike] 2/4 starting app on :$PORT (NOVA_SPIKE_VERIFY=true)"
NOVA_SPIKE_VERIFY=true PORT="$PORT" java -jar "$SPRING_DIR/target/nova-studio-backend-0.1.0-SNAPSHOT.jar" > "$LOG" 2>&1 &
APP_PID=$!

for i in $(seq 1 60); do
  if curl -sf "http://localhost:$PORT/actuator/health" > /dev/null 2>&1; then break; fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then echo "[spike] app exited early"; tail -40 "$LOG"; exit 1; fi
  sleep 1
done

echo "[spike] 3/4 health:"
curl -s "http://localhost:$PORT/actuator/health" | python -m json.tool || curl -s "http://localhost:$PORT/actuator/health"

echo "[spike] startup evidence (flyway/redis):"
grep -E "spike-verify|redis-probe|Flyway|Migrating schema" "$LOG" || true

echo "[spike] 4/4 frontend ccode-task-socket integration test"
(cd "$FRONTEND_DIR" && \
  SPIKE_WS_URL="ws://localhost:$PORT/api/nova/ws" \
  SPIKE_API_BASE="http://localhost:$PORT" \
  npx vitest run src/lib/__tests__/ccode-task-socket.spike.test.ts)

echo "[spike] DONE — all connectivity checks passed"
