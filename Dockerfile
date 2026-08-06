# ============================================================================
# Nova Image Studio — multi-stage production image (T3.5 / WIN-13, ARCH C.7)
#
#   stage 1  frontend-builder : Next.js static export (frontend/out)
#   stage 2  backend-builder  : Maven build of the Spring Boot backend
#   stage 3  runtime          : eclipse-temurin:21-jre (PRD §7) serving
#                              static + API + WS on port 3000
#
# 基础设施（PG/Redis/密钥）由 docker-compose 提供；.env 只含基础设施配置
# （PRD §10.2），AI Key 一律在 DB 中（AES-GCM 加密），不入镜像。
# ============================================================================

# ---- stage 1: frontend static export ---------------------------------------
FROM node:22-slim AS frontend-builder

WORKDIR /app

COPY package.json package-lock.json ./
COPY frontend/package.json frontend/package-lock.json ./frontend/
COPY frontend/ ./frontend/

RUN cd frontend && npm ci && npm run build

# ---- stage 2: Spring Boot backend build ------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS backend-builder

WORKDIR /app/backend-spring

# Resolve dependencies first (cache layer) then compile+package
COPY backend-spring/pom.xml .
RUN mvn -B -ntp -q dependency:go-offline || true

COPY backend-spring/src ./src
RUN mvn -B -ntp -DskipTests package

# ---- stage 3: production runtime -------------------------------------------
FROM eclipse-temurin:21-jre

# HEALTHCHECK 依赖 curl（temurin 镜像不含 wget/curl，显式安装）
RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Spring Boot fat jar (single artifact — no node_modules, no sqlite natives)
COPY --from=backend-builder /app/backend-spring/target/nova-studio-backend-0.1.0-SNAPSHOT.jar ./app.jar

# Frontend static export served by the Spring backend (NOVA_STATIC_DIR)
COPY --from=frontend-builder /app/frontend/out ./frontend/out

# Gallery seed files (mountable from host; DB-ized on first start, T3.1)
COPY backend/prompts.json ./backend/prompts.json
COPY backend/blacklist.json ./backend/blacklist.json

ENV PORT=3000 \
    NOVA_STATIC_DIR=./frontend/out \
    NOVA_PROMPTS_PATH=./backend/prompts.json \
    NOVA_BLACKLIST_PATH=./backend/blacklist.json

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["sh", "-c", "curl -fs http://127.0.0.1:3000/actuator/health | grep -q UP || exit 1"]

CMD ["java", "-jar", "app.jar"]
