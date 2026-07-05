# ============================
# Stage 1: Frontend Builder
# ============================
FROM --platform=$BUILDPLATFORM node:22-slim AS frontend-builder

WORKDIR /app

COPY package.json package-lock.json ./
COPY frontend/package.json frontend/package-lock.json ./frontend/
COPY frontend/ ./frontend/

RUN cd frontend && npm ci && npm run build

# ============================
# Stage 2: Backend Dependencies (compiles native addons for BUILDPLATFORM)
# ============================
FROM --platform=$BUILDPLATFORM node:22-slim AS backend-deps-amd64

WORKDIR /app/backend

# better-sqlite3 needs python3, make, g++ for native compilation
RUN apt-get update \
  && apt-get install -y --no-install-recommends python3 make g++ \
  && rm -rf /var/lib/apt/lists/*

COPY backend/package.json backend/package-lock.json ./

RUN npm ci --omit=dev \
  && apt-get purge -y --auto-remove python3 make g++ \
  && rm -rf /var/lib/apt/lists/*

# ============================
# Stage 3: Production (multi-arch)
# ============================
FROM --platform=$TARGETPLATFORM node:22-slim AS production

WORKDIR /app

ENV NODE_ENV=production

# Install build deps for better-sqlite3 native compilation in target arch
RUN apt-get update \
  && apt-get install -y --no-install-recommends python3 make g++ \
  && rm -rf /var/lib/apt/lists/*

COPY backend/package.json backend/package-lock.json ./

# Install backend deps in target architecture (compiles better-sqlite3 native addon)
# Using --prefer-offline to speed up and avoid lockfile platform mismatch issues
RUN npm install --omit=dev --prefer-offline

# Clean up build deps
RUN apt-get purge -y --auto-remove python3 make g++ \
  && rm -rf /var/lib/apt/lists/*

COPY --from=frontend-builder /app/frontend/out/ ./frontend/out/

RUN mkdir -p /app/backend/data

EXPOSE 3000

CMD ["node", "backend/server.js"]
