# Nova Studio Backend — Spring Boot (M1 P0 backend core)

> 分支：`feature/m1-p0-backend-core` ｜ 关联：WIN-11（M1）｜ 架构：`docs/ARCH-springboot-migration-v1.1.md`
> 前置：M0 spike（`docs/SPIKE-springboot-skeleton-v1.0.md`，版本基线 SB 4.1.0 + Spring AI 2.0.0 + JDK 21）

M1 交付「P0 后端核心」：在不改前端的前提下用 Spring Boot 复刻 Node 后端全部 P0 能力
（任务队列 / 图片 3 协议 / 文本代理 / WebSocket 全量 / 静态托管 / 限流 / 优雅停机），
实现「前端零改动可连」并完成新旧后端 A/B 差异测试（报告：`docs/AB-DIFF-M1-spring-vs-node.md`）。

## 结构

```
backend-spring/
├── pom.xml                     # Maven 单模块（ARCH H11）
├── .env.example                # 基础设施 + 运维开关模板（git-ignored .env 放真实值）
├── src/main/java/com/nova/studio/
│   ├── config/                 # WebSocket / WS 核心 / WebClient / 静态
│   ├── infra/                  # .env 热读（1s TTL）、错误归一化、HttpErrorException
│   ├── imagegen/               # 图片 3 协议路由 + partial_images 流式 + Gemini REST + Grok 自定义
│   ├── storage/                # 图片落盘 / 托管解析 / 清理（磁盘，P2 可换对象存储）
│   ├── task/                   # TaskRepository(PG) / TaskQueueService(slot 并发) / TaskService /
│   │                           # QueueStatsService / RateLimiterService / 启动残留标记 / TTL 清理 / 停机
│   ├── textproxy/              # 4 协议 raw 透传（SSE + JSON，java.net.http）
│   ├── web/                    # REST 控制器 + 全局异常 + 静态托管 + SPA fallback
│   └── ws/                     # NovaWebSocketHandler（全量协议复刻）+ TaskRegistry
├── src/main/resources/
│   ├── application.yml         # ${ENV_VAR:default} 占位符；public schema；10MB 请求体上限
│   └── db/migration/V1__init.sql   # tasks / task_items（Q1: user_id 可空）
└── src/test/java/…             # 101 个测试（单元 + WS e2e + 真实 PG 集成 + HTTP e2e）
```

## M1 覆盖（T1.1–T1.10）

| 任务 | 落点 |
|------|------|
| T1.1 任务存储 | `task/TaskRepository`（PG tasks/task_items + Flyway V1 + 启动残留标记 + 5min TTL 清理） |
| T1.2 任务队列 | `task/TaskQueueService`（slot 并发 50 + 独占特例 + 状态机 + ack 续期 2min） |
| T1.3 图片 3 协议 | `imagegen/ImageGenService`（openai 流式+回退 / google 自定义 REST / grok 自定义） |
| T1.4 图片落盘/托管 | `storage/ImageStorageService` + `web/ImageController`（Cache-Control 1h + 扩展名兜底） |
| T1.5 文本代理 | `textproxy/TextProxyService` + `web/TextProxyController`（4 协议 raw 透传 + SSE） |
| T1.6 模型列表/队列状态 | `web/ModelListController` + `web/QueueStatusController` |
| T1.7 WebSocket 全量 | `ws/NovaWebSocketHandler`（200/500 上限、心跳终止、200ms 广播节流） |
| T1.8 静态托管 | `web/StaticController`（SPA fallback + 404 + 路径遍历防护） |
| T1.9 优雅停机 | `task/ShutdownCoordinator` + `StaleTaskMarker`（等飞任务 + 残留清理 + .env 启动读取） |
| T1.10 限流 | `task/RateLimiterService`（IP + apiKeyHash 双维度、Retry-After、待处理数上限） |

**Q1/Q2/Q4（用户已拍板）**：tasks.user_id 可空（NULL=系统/迁移遗留）；M1 前端未改仍传
apiKey/baseUrl 旧入参，服务端原样使用（modelId 服务端解析随 M2 设置/注册表 API 落地）；
`NOVA_ACCEPT_NEW_TASKS`/`NOVA_REJECT_NEW_TASKS` 保留 .env（A.5-Q4/H4）。

## 运行

前置：JDK 21、Maven 3.9+、外部 PostgreSQL（`nova` 库）+ Redis（`.env`）。

```bash
cd backend-spring
cp .env.example .env            # 填入真实 DB/Redis 连接（.env 已被 git 忽略，严禁提交）
export JAVA_HOME=<jdk21>
mvn spring-boot:run             # 或先 `mvn package` 后 java -jar target/*.jar
```

启动后（默认 8080）：
- 健康检查：`GET /actuator/health`（db / redis UP）
- 任务：`POST /api/nova/tasks`（与 Node 后端同一契约，202 `{taskId}`）
- WS：`ws://localhost:8080/api/nova/ws`（subscribeTasks/subscribeQueue/ping 全量协议）
- 队列：`GET /api/nova/queue-status`；图片：`GET /api/nova/images/:taskId/:index`
- 文本代理：`POST /api/nova/proxy/text`；模型列表：`GET /api/nova/proxy/models`
- 广场/黑名单/配置：`/api/nova/prompts`、`/blacklist`、`/config`、`/prompt-gallery/verify`
- 静态：`/`（`frontend/out` + SPA fallback）

## 测试

```bash
# 单元 + WS e2e + HTTP e2e（无需外部服务也能跑通全部纯单元测试）
mvn test

# 含外部服务器集成测试（从 .env 导出 DB_HOST/REDIS_HOST 后执行）
export DB_HOST=... DB_PORT=... DB_USERNAME=... DB_PASSWORD='...' DB_NAME=nova
export REDIS_HOST=... REDIS_PORT=... REDIS_PASSWORD='...'
mvn test
```

集成测试（`FlywayPostgresIntegrationTest` / `RedisConnectivityIntegrationTest` /
`TaskApiE2EIntegrationTest`）在无 `DB_HOST`/`REDIS_HOST` 环境变量时自动跳过（CI 安全）。

### 与 Node 后端 A/B 差异测试（T1.11）

见 `docs/AB-DIFF-M1-spring-vs-node.md` 与 `backend-spring/scripts/ab-diff/`：
同一前端（`frontend/out`）分别对接 Node 后端（3000）与 Spring 后端（8080），
对 12 条清单 P0 项做逐字段比对（mock 上游，无需真实 Key）。

## 与 M0 的差异备忘

- Flyway 从 `nova_spike` schema 迁移到用户确认的 `nova` 库 public schema（V1 重写为真实 DDL）。
- 移除 spike 专用 `SpikeConnectivityVerifier` / `OpenAiAiConfig`；`RedisProbe` 保留（连通性测试用）。
- 限流按 ADR-9 语义实现；Bucket4j 构件在本环境镜像不可解析（2026-08-03 核实），
  采用与 Node `consumeRateLimit` 逐行等价的固定窗口桶（见 `RateLimiterService` 注释）。
