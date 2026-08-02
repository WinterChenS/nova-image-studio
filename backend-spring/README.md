# Nova Studio Backend — Spring Boot skeleton (M0 spike)

> 分支：`spike/m0-skeleton` ｜ 关联：WIN-10（M0 spike）｜ 架构：`docs/ARCH-springboot-migration-v1.1.md`

最小骨架 spike：**Spring Boot 4.1.0 + Spring AI 2.0.0 + JDK 21**，验证 Flyway + PostgreSQL + Redis 连通、
Spring AI 能力、自定义 WebClient（`partial_images` / Gemini image）、WebSocket 最小协议与前端联通。

## 版本基线（spike 实测锁定，见 ADR-1）

| 组件 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 4.1.0 | 官方 GA（2026-06-10） |
| Spring AI | 2.0.0 | 官方 GA（2026-06-12），与 SB 4.1.0 为官方测试组合 |
| JDK | 21 (Temurin 21.0.3) | LTS |
| Flyway | 12.4.0（Boot 托管） | Boot 4 需 `spring-boot-starter-flyway`（模块化） |
| PostgreSQL 驱动 | 42.7.11（Boot 托管） | |
| Lettuce | 7.5.2（Boot 托管） | |

> ⚠️ **spike 发现**：Spring AI 2.0.0 GA（Maven Central 发布物）**没有** `GoogleGenAiImageModel` /
> `spring-ai-google-genai-image` 模块（已对实际 jar 核实）——ARCH §B.4/ADR-5 的「Gemini image 原生支持」
> 基于 `main` 分支源码（超前于 2.0.0 发布线）。因此 Gemini 图片路径以**自定义 WebClient REST 调用**
> 实现并验证（与 Node 后端 `generateNovaGeminiImage` 协议一致，含 `imageConfig.imageSize/aspectRatio`）。
> 详见 `docs/SPIKE-springboot-skeleton-v1.0.md`。

## 结构

```
backend-spring/
├── pom.xml                     # Maven 单模块（ARCH H11）
├── .env.example                # 基础设施连接模板（git-ignored .env 放真实值）
├── src/main/java/com/nova/studio/
│   ├── NovaStudioApplication.java
│   ├── config/                 # OpenAiAiConfig / WebSocketConfig / WsCoreConfig / SpikeConnectivityVerifier
│   ├── imagegen/               # ImagePayloadExtractor / PartialImagesClient / GeminiImageClient
│   ├── redis/                  # RedisProbe（SET/GET 探测）
│   ├── web/                    # SpikeTaskController（REST→WS 广播演示，M1 替换为真实任务 API）
│   └── ws/                     # NovaWebSocketHandler / TaskRegistry（WS 最小协议）
├── src/main/resources/
│   ├── application.yml         # 全部 ${ENV_VAR:default} 占位符（.env → 环境变量注入）
│   └── db/migration/V1__init.sql   # nova_spike schema 探测表（M1 替换为真实 DDL）
└── src/test/java/…             # 39 个测试（单元 + WS e2e + 集成，见下）
```

## 运行

前置：JDK 21、Maven 3.9+、外部 PostgreSQL + Redis（`.env`）。

```bash
cd backend-spring
cp .env.example .env            # 填入真实 DB/Redis 连接（.env 已被 git 忽略，严禁提交）
export JAVA_HOME=<jdk21>
mvn spring-boot:run
# 或：NOVA_SPIKE_VERIFY=true mvn spring-boot:run   # 启动时打印 Flyway/Redis 连通证据
```

启动后：
- 健康检查：`GET /actuator/health`（db / redis 均为 UP）
- WS：`ws://localhost:8080/api/nova/ws`
- spike 任务广播演示：`POST /api/nova/spike/tasks` `{"id":"x","status":"processing"}`

## 测试

```bash
# 单元 + WS e2e（39 个测试，无需外部服务）
mvn test

# 含外部服务器集成测试（从 .env 导出 DB_HOST/REDIS_HOST 等后执行）
export DB_HOST=... DB_PORT=... DB_USERNAME=... DB_PASSWORD='...' DB_NAME=postgres
export REDIS_HOST=... REDIS_PORT=... REDIS_PASSWORD='...'
mvn test
```

集成测试（`FlywayPostgresIntegrationTest` / `RedisConnectivityIntegrationTest`）在无
`DB_HOST`/`REDIS_HOST` 环境变量时自动跳过（CI 安全）。

### 前端 ccode-task-socket 联通（T0.4）

```bash
# 1) 启动 Spring 后端（如上）
# 2) 运行真实前端 WS 客户端集成测试
cd frontend
export SPIKE_WS_URL=ws://localhost:8080/api/nova/ws
export SPIKE_API_BASE=http://localhost:8080
npx vitest run src/lib/__tests__/ccode-task-socket.spike.test.ts
```

验证：订阅立即推送、REST→WS 广播、queueStatus、心跳存活（25s ping 周期）、断线重连+重订阅。

## 测试清单（39）

| 类 | 数量 | 覆盖 |
|----|------|------|
| `ImagePayloadExtractorTest` | 10 | 图片 payload 提取 / SSE 解析（Node 逻辑移植） |
| `PartialImagesClientTest` | 4 | `partial_images` 流式 + 非流式回退 + 错误传播（T0.3） |
| `GeminiImageClientTest` | 4 | Gemini `generateContent` + imageConfig 透传（T0.2 修正路径） |
| `SpringAiOpenAiSpikeTest` | 3 | SA 2.0.0 OpenAI chat 流式/非流式 + image 非流式（T0.2） |
| `NovaWebSocketE2ETest` | 7 | WS 协议 e2e：ping/pong、订阅/广播、错误码、心跳存活（T0.4） |
| `NovaWebSocketHandlerHeartbeatTest` | 2 | 心跳终止路径（mock session） |
| `TaskRegistryTest` | 6 | 订阅上限 / 终态自动退订 / 广播目标 |
| `FlywayPostgresIntegrationTest` | 2 | 真实 PG：Flyway 迁移 + 建表（T0.1，需 DB_HOST） |
| `RedisConnectivityIntegrationTest` | 1 | 真实 Redis SET/GET（T0.1，需 REDIS_HOST） |

## 说明与边界（M1 承接）

- `SpikeTaskController` 与 `TaskRegistry`（内存）为 spike 演示用；M1 替换为 PostgreSQL
  `tasks`/`task_items` + 真实任务 API（契约不变）。
- `V1__init.sql` 仅在共享 `postgres` 库内建 `nova_spike` schema 探测表（M0 连通性验证用，
  经协调者确认；M1 使用独立库/独立 schema 的真实 DDL）。
- 心跳/订阅上限等参数可通过 `nova.ws.*` 配置调整。
