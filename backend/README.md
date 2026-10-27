# TraceMind

**日志采集 + 智能根因诊断平台。** Java 侧负责采集、存储、检索、事件推送；Python 侧负责 LLM / 规则推理。两边通过 HTTP + SSE 打通，形成「日志进来 → 秒级检索 → 一键诊断 → 实时流式结论 → 自动出报告」的完整闭环。

```
日志源（文件 / 实时追加）
   │
   ├─ 导入 ──→ 流式逐行解析 ──→ Elasticsearch（log_entry）
   └─ 采集 ──→ NIO WatchService 增量 ──→ 同上
                                          │
                                   检索 / 聚合 / 聚类
                                          │
                          触发诊断 ──→ Python trace-mind-ai
                                          │  ① Drain3 模板（0 token）
                                          │  ② 规则引擎 ×5（0 token，命中即返回）
                                          │  ③ LangChain Agent + 6 工具（有预算上界）
                                          │
                                     SSE 事件流
                                          │
                          先落 agent_event → 再推 WebSocket → 前端打字机
                                          │
                                     诊断报告 / 案例库沉淀
```

---

## 目录

- [核心特性](#核心特性)
- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [目录结构](#目录结构)
- [接口一览](#接口一览)
- [关键设计](#关键设计)
- [配置参考](#配置参考)
- [常见坑](#常见坑)
- [已知问题](#已知问题)
---

## 核心特性

| 特性 | 说明 |
|---|---|
| **大文件流式导入** | 字符缓冲流逐行读 + 攒批写，内存占用与文件体积解耦；单文件上限 100MB，单请求 500MB |
| **NIO 实时采集** | `WatchService` 事件驱动替代轮询；位点「Redis Hash（offset+lines 原子推进）+ MySQL 快照」双存储，重启与 stop→start 均不丢进度、行号不错位 |
| **秒级检索** | ES 全文检索（IK 分词）+ 时间范围 + 级别过滤；错误聚类按日志模板聚合 |
| **三阶段诊断漏斗** | Drain3 模板提取 → 规则引擎（5 类分析器）→ Agent 深诊。**前两阶段零 LLM 调用**，只有长尾才花钱 |
| **实时事件流** | `agent_event` 先落库、再推 WebSocket；Redis `INCR` 生成 seq 保证顺序可校验、断线可补齐 |
| **新错误雷达** | 以日志模板 ID 为错误指纹，新指纹出现才报新，避免告警风暴；冷启动静默建基线 |
| **自动诊断闭环** | 雷达发现新 ERROR → 限流 → 自动触发诊断 → 自动出报告；开关可在设置页切换（存 Redis） |
| **三层写入容灾** | ES 写入失败 → 退避重试 → 本地 `fallback/` 文件暂存 → 定时任务逐条确认后重放（仅采集链路；导入刻意不落盘） |
| **多租户隔离** | JWT + 行级 `owner_id` 过滤 + `OwnershipGuard` 统一归属校验；访问他人资源返回 40400 防枚举 |

---

## 系统架构

平台由三个工程组成，在本仓库中是三个子目录（`backend/` `python-ai/` `frontend/`），但**各自独立启动**：

| 工程 | 本仓库位置 | 端口 | 职责 |
|---|---|---|---|
| **backend**（本文档） | `backend/` | 8080 | 采集、导入、检索、诊断编排、事件推送、报告、鉴权、持久化 |
| **python-ai** | `python-ai/` | 8100 | Drain3 模板、规则引擎、LangChain Agent、Chroma 案例库 |
| **frontend** | `frontend/` | 5173 | Vue3 前端 |

> 三端也可拆成独立仓库分别开发；那种布局下本机路径分别是
> `C:\Users\32229\IdeaProjects\TraceMind`（后端）、
> `C:\Users\32229\IdeaProjects\trace-mind-ai`（AI 服务）、
> `E:\ai-develop\AIcoding\trace-mind-web`（前端）。

Java 侧**不直接调用任何大模型**——所有 LLM / 模板 / 规则推理都在 Python 侧，Java 只做调度、持久化和转发。这样拆的好处：AI 服务挂掉时，平台的采集、检索、规则诊断全部照常，只有 Agent 深诊降级。

数据分层：

- **MySQL**：只存元数据（项目、任务、会话、事件、报告、基线），日志正文一条都不存
- **Elasticsearch**：日志正文的唯一归宿，索引 `log_entry`
- **Redis**：采集位点与文件指纹（`tracemind:collector:*`）、诊断 seq、WS ticket、自动诊断开关。key 命名统一收口在 `common/RedisKeys`，父前缀 `tracemind:` 便于一条 pattern 扫出全平台占用

---

## 技术栈

| 层 | 选型 | 版本 |
|---|---|---|
| 框架 | Spring Boot | 3.4.5 |
| JDK | Java | 17 |
| ORM | MyBatis（XML 在 `resources/mapper/`） | 3.0.4 |
| 检索 | elasticsearch-java 官方客户端 | 8.10.4 |
| 存储 | MySQL + Redis（Lettuce） | MySQL 8/9，Redis 任意 6+ |
| 鉴权 | Spring Security + JJWT（HS256） | 0.12.6 |
| 流式调用 | WebFlux `WebClient`（SSE 透传） | — |
| 推送 | 原生 WebSocket（非 STOMP） | — |
| 其他 | Lombok、AOP、Validation、HikariCP | — |
| AI 侧 | FastAPI + LangChain + Drain3 + Chroma | Python 3.11 |
| 前端 | Vue3 + Vite + Element Plus + Pinia + axios | pnpm |

---

## 快速开始

### 0. 环境要求

- **JDK 17**、Maven 3.9+
  > ⚠️ 跑测试必须用 17。若 `JAVA_HOME` 指向 JDK 25，Mockito 会报 `cannot mock this class: StringRedisTemplate`——看着像测试写错，实为运行时不兼容。见常见坑 10。
- MySQL（建库 `tracemind`）、Redis、Elasticsearch 8.x（**需安装 IK 分词插件**）
- Python 3.11（AI 服务）
- Node 18+ / pnpm（前端，可选）

### 1. 初始化数据库

```sql
CREATE DATABASE tracemind DEFAULT CHARACTER SET utf8mb4;
```

执行 `src/main/resources/db/schema.sql`，建 8 张表：`project`、`log_monitor_task`、`import_task`、`agent_session`、`agent_event`、`chat_message`、`report`、`error_baseline`。

> ⚠️ `users` 表（多租户）**不在 schema.sql 里**，需手动补建：
>
> ```sql
> CREATE TABLE users (
>   id            VARCHAR(64) PRIMARY KEY,
>   email         VARCHAR(255) NOT NULL UNIQUE,
>   password_hash VARCHAR(255) NOT NULL COMMENT 'bcrypt 哈希',
>   nickname      VARCHAR(100),
>   created_at    DATETIME,
>   updated_at    DATETIME
> );
> ```

ES 索引 `log_entry` 不用手动建——启动类 `LogIndexInitializer` 会按 `resources/db/es-log_entry-index.json` 自动创建。

### 2. 改配置

编辑 `src/main/resources/application.yml`（敏感项建议用环境变量覆盖）：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://127.0.0.1:3306/tracemind` | 库内时间统一存 UTC，URL 已带时区参数，**别删** |
| `spring.data.redis.host/port` | `192.168.100.128:6379` | 密码走 `${REDIS_PASSWORD}` |
| `spring.elasticsearch.uris` | `http://192.168.100.128:9200` | 有账号密码时打开 username/password 注释 |
| `tracemind.python.base-url` | `http://127.0.0.1:8100` | AI 服务地址 |
| `jwt.secret` | 开发用占位串 | **生产必须覆盖**，HS256 要求 ≥32 字节 |
| `server.port` | 8080 | — |

支持的环境变量：`DB_PASSWORD`、`REDIS_PASSWORD`、`JWT_SECRET`。

### 3. 启动 AI 服务

```bash
cd ../python-ai
.venv/Scripts/python -m trace_mind_ai      # Windows；Linux/macOS 用 source .venv/bin/activate 后 python -m trace_mind_ai。监听 8100
```

### 4. 启动后端

```bash
# 在 backend/ 目录下。优先用仓库自带的 wrapper，避免依赖本机 Maven 安装位置
./mvnw spring-boot:run                      # Linux / macOS
mvnw.cmd spring-boot:run                    # Windows（Git Bash 下直接敲 mvn 会报 classworlds 错，务必带 .cmd）
```

⚠️ 如果你本机 shell 里存在 `SERVER__PORT` 这类环境变量，Spring 会把它当成 `server.port` 吃掉（双下划线是 relaxed binding 的层级分隔符）。此时显式指定端口：

```bash
mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
```

或打包运行：

```bash
mvnw.cmd package -DskipTests
java -jar target/TraceMind-0.0.1-SNAPSHOT.jar --server.port=8080
```

### 5. 启动前端（可选）

```bash
cd ../frontend
pnpm install
pnpm dev        # http://localhost:5173
```

`.env.development` 里 `VITE_USE_MOCK=false`、`VITE_API_BASE=/api`；Vite 已代理 `/api` 和 `/ws` 到 `127.0.0.1:8080`。

### 6. Docker（仅后端）

```bash
docker build -t tracemind:latest .
docker run -p 8080:8080 \
  -e DB_PASSWORD=xxx -e REDIS_PASSWORD=xxx -e JWT_SECRET=xxx \
  tracemind:latest
```

注意 `Dockerfile` 是两阶段构建（maven:3.9-temurin-17 → eclipse-temurin:17-jre），**只打 Java 服务**；MySQL / Redis / ES / AI 服务仍需外部提供，且容器内的 `127.0.0.1` 不是宿主机，相关地址要改成实际主机名。

### 7. 验收

```bash
curl http://127.0.0.1:8080/api/settings/health     # 依赖探活：MySQL / Redis / ES / Python
curl http://127.0.0.1:8080/api/settings/model      # 模型信息（透传展示）
```

系统不预置账号，用注册接口创建第一个用户：

```bash
curl -X POST http://127.0.0.1:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"Pass@1234","nickname":"you"}'
```

### 8. 跑测试

```bash
# Git Bash / PowerShell 都要先切到 JDK 17，否则 Mockito 起不来（常见坑 10）
export JAVA_HOME="/c/Users/32229/.jdks/ms-17.0.17"      # Git Bash；换成你本机的 JDK 17
$env:JAVA_HOME="C:\Users\32229\.jdks\ms-17.0.17"        # PowerShell
./mvnw test                                             # Windows 下用 mvnw.cmd test
```

当前覆盖 10 个测试类 / **97 个用例全部通过**（含此前恒失败的 `contextLoads`）：位点存储降级与回落（6）、监控状态接口不再 50000（5）、采集行号连续性与失败子集处置（6）、兜底重放的文件处置（7）、Redis key 一致性（5）、工具方法防环与时间/字符串行为（9）、诊断事件流水线（6）、WS 握手鉴权与 Origin（14）、上传接管（7）、追问流式链路（9）、VO 转换器（9）。

> **关键修复都做过变异验证**：A1（退回"部分失败也删文件"→ 2 条变红）、A2（退回"任一行坏即放弃整文件"→ 1 条变红）、`rootMessage` 防环（退回裸 `while` → **测试进程挂死 150 秒被强杀**，因为该 bug 的表现是死循环而非断言失败）、ID 归一（去掉文件名归一 → 8 条跨 3 个类变红）、fail-close（配置为 null 时改回放行 → 精确抓到那条）、上传接管（不真正落盘 → 3 条变红）、SSE 双重终止（去掉 `break` → 3 条变红）、字段搬运遗漏（漏掉 `lineNumber` → 精确抓到对应那行断言）。均恢复后全绿。
>
> ⚠️ **做变异验证后必须重跑一次全量**。本轮有一次恢复代码时漏删了一行 `staged.add(...)`，导致同一文件被登记两次；它单跑碰巧通过、全量才暴露，而我一开始还误判成测试夹具的问题、白改了一版测试。这类残留只有全量跑能抓住。

---

## 目录结构

```
src/main/java/org/example/tracemind/
├── TraceMindApplication.java
├── aiclient/        PythonClient：WebClient 调 AI 服务，带熔断器（3 次失败→断开，30s 试探）
├── collector/       采集器：WatchServiceLogCollector / CollectorRegistry / FallbackReplayJob
│                    + CollectorDependencies / CollectorFactory（依赖打包与创建入口）
│                    + CollectorOffsetStore（位点读写 + Redis 故障回落 MySQL）
├── common/          ApiResponse、BizException、ResultCode、IdGenerator、PageResult、全局异常处理
│   ├── enums/       日志级别、事件类型、会话状态、导入状态、工具名等
│   ├── RedisKeys    ★ 所有 Redis key 的唯一命名出口（父前缀 tracemind: + TTL + 失败语义注释）
│   └── Utilx        ★ rootMessage（限步防环）/ toInstant / isBlank 等的唯一实现
├── config/          只放装配类：Security / WebSocket / Async / Jackson / TraceMindProperties
│                    （启动钩子在 bootstrap/、领域组件已归各自包 —— 见重构评估 R-1.5）
├── bootstrap/       ImportStartupCleanup（僵尸导入任务清理）、CollectorRecoveryRunner（重启续采）
├── controller/      9 个 REST 控制器（见接口一览）
├── diagnosis/       DiagnosisWsHandler（Spring TextWebSocketHandler）、WsHandshakeInterceptor、
│                    DiagnosisEventPipeline（发号→落库→推送）、DiagnosisEventPublisher（端口）、
│                    WsSessionRegistry、AutoDiagnosisSwitch
├── es/              EsLogRepository（检索+聚合）、EsDocId（★ 文档 ID 唯一真源）、
│                    BulkResult（逐条写入结果）、AggregateResult（中性聚合事实）、
│                    LogVoConverter（★ Doc/聚合 → VO 的唯一出口）、LogIndexInitializer
├── mapper/          MyBatis Mapper 接口（XML 同名放 resources/mapper/）
├── pojo/
│   ├── entity/      与表一一对应的实体
│   ├── dto/         入参
│   └── vo/          出参
├── security/        JwtService、JwtAuthenticationFilter、CurrentUser
└── service/         业务接口 + impl；OwnershipGuard 为统一归属校验
```

命名约定：ORM 映射文件用「域 + 角色」两段式（如 `AgentEventMapper.xml`）；接口返回统一 `{code, message, data}`，`code=0` 为成功。

---

## 接口一览

所有接口前缀 `/api`，HTTP 状态码恒为 200（认证失败例外：真实 401，前端据此跳登录），业务状态看 `code`：`0` 成功 / `40000` 参数错 / `40400` 不存在或无权限 / `50000` 服务端错 / `50300` 依赖不可用。

| 分组 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 认证 | POST | `/api/auth/register` | 注册 |
| | POST | `/api/auth/login` | 登录，返回 JWT |
| | GET | `/api/auth/me` | 当前用户 |
| 项目 | GET | `/api/projects` | 列表（只返回自己的） |
| | POST | `/api/projects` | 新建 |
| | GET/PUT/DELETE | `/api/projects/{id}` | 详情 / 修改 / 删除 |
| 日志 | GET | `/api/projects/{projectId}/logs` | 检索 |
| | GET | `/api/projects/{projectId}/logs/stats` | 统计 |
| | GET | `/api/projects/{projectId}/logs/{logId}` | 单条详情 |
| 导入 | POST | `/api/projects/{projectId}/logs/import` | 上传日志文件（multipart） |
| | GET | `/api/logs/import/{taskId}/progress` | 导入进度 |
| 监控采集 | POST | `/api/projects/{projectId}/monitor/start` | 启动实时采集 |
| | POST | `/api/projects/{projectId}/monitor/stop` | 停止（保留位点，下次续读） |
| | GET | `/api/projects/{projectId}/monitor` | 采集状态。响应含 `aiDegraded` / `positionDegraded` 两个独立降级标记 |
| 错误雷达 | GET | `/api/projects/{projectId}/errors/new` | 新错误列表 |
| | POST | `/api/projects/{projectId}/errors/{clusterId}/ack` | 确认已读 |
| 诊断 | POST | `/api/diagnosis/trigger` | 触发诊断 |
| | GET | `/api/diagnosis/sessions` | 会话列表 |
| | GET | `/api/diagnosis/{sessionId}` | 会话详情 |
| | POST | `/api/diagnosis/{sessionId}/chat` | 追问（**SSE**） |
| | POST | `/api/diagnosis/{sessionId}/ws-ticket` | 换取一次性 WS 握手票据 |
| 报告 | POST | `/api/reports/generate` | 生成报告 |
| | GET | `/api/reports` | 报告列表（返回完整 Markdown） |
| | GET | `/api/reports/{id}` | 报告详情 |
| | GET | `/api/reports/{id}/download` | 下载 |
| 设置 | GET | `/api/settings/health` | 依赖探活 |
| | GET | `/api/settings/model` | 模型信息 |
| | GET/PUT | `/api/settings/auto-diagnosis` | 自动诊断开关 |

字段级契约以代码为准：入参看 `pojo/dto/`、出参看 `pojo/vo/`，统一响应包装见 `common/ApiResponse`。

---

## 关键设计

**诊断三阶段漏斗。** 成本是第一设计约束：阶段 1（Drain3 模板提取）和阶段 2（5 类规则分析器：NPE / OOM / 连接拒绝 / SQL 错误 / 超时）都是零 token，规则命中直接返回，**一次大模型都不调**；只有长尾问题才下沉到阶段 3——LangChain `create_agent` 编排的 Agent + 6 类工具（日志检索、相似案例、规则匹配、频次统计、源码定位、健康检查）。

**Agent 的三道闸门。** 工具调用上限 6 次（自定义 middleware）、LangGraph 递归上限 25、总耗时 90 秒。三者互相独立，任一生效都能止损，保证诊断耗时有上界、不会无声烧钱。

**事件流顺序保证。** Redis `INCR diag:seq:{sessionId}` 生成单调递增 seq，事件**先落 `agent_event` 表、再推 WebSocket**，断线重连按 seq 补齐。WebSocket 强制一次性 ticket 鉴权（多租户下拿 sessionId 就能偷看别人诊断过程）。

**三层写入容灾（仅采集链路）。** ES 写入失败先退避重试；仍失败则写本地 `./fallback/es-retry`（单文件上限 10000 行）；`FallbackReplayJob` 每分钟扫描重放。**宁可慢，不可丢。**

本轮修复后这条链路的语义是：
- **逐条确认，不是"没抛就算成功"** —— `bulkInsert` 返回 `BulkResult`，ES bulk 的部分失败（HTTP 200 + `errors=true`）不再被吞掉；
- **只重试／只落盘失败子集** —— 已写成功的条目不会被重复投递；
- **只有全成功才删兜底文件** —— 部分失败时用剩余原文原子重写（`.tmp` → move），文件收敛到只剩真失败的条目；
- **一行坏 JSON 不阻塞同文件其余数据** —— 坏行隔离进 `corrupt/` 留档；
- **毒消息有出口** —— 重试超 `max-replay-attempts`（默认 10）移入 `dead/` 并告警，不会永远每分钟陪跑。

⚠️ 两点边界仍然成立：**导入链路刻意不做本地兜底**（用户主动操作、重传成本低，落盘反而制造"完成但数据不全"的假象；改为如实报错 + 精确计数）；且 `./fallback` 在容器里通常不是持久卷，镜像重建即清零。若要跨机可靠，建议先用 Redis Stream 替掉手搓文件队列（拿到消费确认与积压可观测，且不新增运维对象），而不是直接上 Kafka/RabbitMQ——后者解决的是一对多广播与跨进程路由，当前单节点单消费者用不上。

**多租户。** 登录后每个请求由 `JwtAuthenticationFilter` 解析出 userId 塞进 SecurityContext，业务层统一走 `CurrentUser.id()`；所有查询强制带 `owner_id` 过滤，并用 `OwnershipGuard.requireOwned()` 校验归属。访问他人资源返回 **40400 而不是 40300**——40300 等于告诉对方「这个 ID 存在」，会被用来枚举探测。

**Python 侧熔断。** `PythonClient` 内置熔断器：连续 3 次失败即断开，30 秒后放一个请求试探，成功才合闸。AI 服务不可用时只降级 Agent 深诊，平台其余功能不受影响。

---

## 配置参考

| 前缀 | 关键项 | 默认 |
|---|---|---|
| `tracemind.python` | `base-url` / `health-timeout-ms` | `http://127.0.0.1:8100` / 2000 |
| `tracemind.model` | `provider` / `model` / `base-url` | 仅透传给前端展示，真实调用在 Python 侧 |
| `tracemind.upload` | `staging-dir` | `./upload-staging`（导入文件的接管目录，见 🔴 专节；进程中途被强杀可能残留，需定期清） |
| `tracemind.fallback` | `dir` / `max-lines-per-file` / `max-replay-attempts` | `./fallback/es-retry` / 10000 / 10（仅采集链路生效；超上限的毒消息移入 `dead/`） |
| `tracemind.websocket` | `enable-auth` / `max-connections` / `heartbeat-interval-sec` / `replay-send-timeout-ms` | true / 1000 / 20 / 200 |
| `diagnosis` | `auto-trigger` / `auto-trigger-limit` | true / 3 |
| `jwt` | `secret` / `expire-seconds` | 开发占位 / 604800（7 天） |
| `server.tomcat` | `relaxed-query-chars` | `[` `]`（数组参数形如 `level[]=` 需要） |

---

## 常见坑

搬过来的几条血泪，改动前先看一眼，能省半天：

1. **时区**：库内 DATETIME 存 UTC，JDBC URL 必须带 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`，两个参数**成对出现**才生效；只写 `serverTimezone` 没用。实体用 `LocalDateTime`，VO 用 `Instant`。⚠️ 但项目里目前并存三套相反假设（固定 UTC / `systemDefault()` / 硬编码 +08），改动时间相关逻辑前先确认你碰到的是哪一套。
2. **MyBatis `@Param`**：≥2 个参数时每个都要加；单个 POJO 参数**千万别加**（会被包成 ParamMap，报 `code:50000`）。
3. **ES 近实时**：bulk 写入后立刻查会得到 0 条，要等约 1 秒 refresh；自动诊断链路里固定等 2.5 秒。
4. **聚合场景别信 `hit.id()`**：top_hits 聚合下 `hit.id()` 经常为空，取 `source.getId()`（文档 ID 是 `项目:文件:行号`，写入时已全量落进 source）。
5. **SSE token 不要 trim**：前导空格是英文单词的分隔符，trim 掉后 Markdown 格式会崩。
6. **Python 没有方法重载**：同一个类里同名方法后定义覆盖先定义。middleware 曾同时写同步/异步两个 `after_model`，框架按同步调用拿到 coroutine 对象直接抛错，且被降级逻辑吃掉，导致 Agent 阶段长期「看起来正常但其实没跑」。（已修复，勿回退。）
7. **双语言契约要两边同改**：改接口字段时 Java 和 Python 必须同步，Python 侧 `extra="ignore"` 会让漏字段静默通过。⚠️ **Java↔前端同理**：`MonitorStatusVO.degraded` 曾在后端存在很久，而前端 types 和 openapi 里根本没有这个字段——单方面加字段不会报错，只会静默失效。
8. **javac 报错要分层看**：先看是不是 Lombok 注解处理失败——它一崩会掩盖几十上百条「找不到符号」。
9. **采集位点 key 已从 String 改为 Hash**：旧 `offset:{taskId}`（String）→ 新 `collector:pos:{taskId}`（Hash，含 `offset` / `lines` 两个 field）。原因：行号由 linesRead 累加得出，与 offset 分开存会出现「offset 恢复到第 5000 行、linesRead 归零」的半恢复状态，导致新行算成第 1 行起、**静默覆盖 ES 里最早的文档**。两者同一条 Hash 才能保证原子推进。旧的 `offset:*` key 不再被读取，可安全删除。
10. **跑测试必须用 JDK 17**：本机默认 `JAVA_HOME` 若是 JDK 25，Mockito inline mock maker 会直接报 `Mockito cannot mock this class: StringRedisTemplate`，看着像测试写错了，其实是运行时不兼容。
    ```powershell
    $env:JAVA_HOME="C:\Users\32229\.jdks\ms-17.0.17"   # 换成你本机的 JDK 17 路径
    ./mvnw test                                        # Windows 用 mvnw.cmd test
    ```
11. **`Files.writeString(..., APPEND)` 不会创建文件**：目标不存在时抛 `NoSuchFileException`。测试里造日志文件要写 `CREATE, APPEND`。
12. **改深层嵌套 JSON（如 openapi）后务必验证**：手工编辑极易少一层闭合括号。用 `ConvertFrom-Json` 或 `jq` 跑一遍再提交。
13. **ES bulk 的部分失败不抛异常**：HTTP 200 + `errors=true` + 逐条结果，客户端拿不到 exception。所以「调用没抛 = 全部成功」这个推断在 bulk 上是错的 —— 必须遍历 `resp.items()` 看每条的 `error()`。（曾因此让兜底重放删掉含失败条目的文件、让导入谎报 DONE。）另注：8.10.4 客户端的逐条类型叫 `BulkResponseItem`，新版才改名 `BulkItemResponse`，别照抄网上示例。
14. **靠下标对齐 `docs.get(i)` ↔ `items().get(i)` 有前提**：ES 保证 items 顺序与提交顺序一致，但一旦引入"只重投失败子集"，就必须确保传入列表正是本次实际提交的列表。这个前提已写进代码注释，改动时别丢。
15. **PowerShell 里的 .NET API 不认 PowerShell 的位置**：`[System.IO.File]::ReadAllBytes("相对路径")` 按 **.NET 当前目录**解析，不是 `Set-Location` 之后的位置，会读到错误文件或空。另外 PS 5.1 读无 BOM 的 UTF-8 脚本会把中文串截断成语法错误——校验脚本尽量只用 ASCII 匹配。

---

## 已知问题

## 已知问题

1. **同一语义多份实现**（历史事故反复出现的形状，也是本项目的头号结构性风险）：分页兜底 DTO 有规范实现但两处 Service 手写绕过 —— 这是目前唯一还没收口的重复。ES 文档 ID、UTC 时间转换、`rootMessage` 均已归一（见下方"已修复"）。
2. **WS 历史回放失去了单条发送超时**（Spring WebSocket 迁移的代价）。旧版用 `getAsyncRemote() + Future.get(timeout)` 防慢客户端拖住容器线程；Spring 的 `WebSocketSession.sendMessage` 是同步语义、没有等价的带超时异步句柄。现在的替代是「发送异常立即放弃剩余回放 + 每会话发送串行化」——能挡住坏连接，但挡不住"活着且极慢"的连接。
3. **导入进度条分母是估算值**：`estimateLines` 用 `bytes / 120` 猜总行数。「谎报完成」已修（进度只累加确实写入 ES 的行），但百分比本身仍会偏差 —— 属既有设计。
4. **自动化测试仅覆盖 Java 侧一部分**：Java 侧 8 个测试类 / 79 个用例全部通过（含此前恒失败的 `contextLoads`，随 WS 迁移一并解决）；Python 侧有 unit / contract / integration 共 8 个文件。报告生成与导入端到端流程仍靠手工 curl 验证。
5. **`schema.sql` 与真实库不同步**（`users` 表缺失、`log_monitor_task.degraded` 列缺失）。本项目以真实库为准，上线前仍需人工对齐。
6. **暂存目录多占一份磁盘**：接管上传文件的代价（单请求上限 500MB）。清理逻辑在 `runImport` 的 finally 与 `startImport` 的 catch 里，两处刻意不重叠；若进程在导入中途被强杀，那一轮的暂存文件会残留，需靠运维定期清 `./upload-staging`。

### 🔴 本轮重点修复：导入的「失效句柄」缺陷（R-4.2）

> **这是本轮最值得注意的一处改动，因为它修的是"平时正常、偶尔炸、报错看不出根因"的间歇性故障。**

**症状**：上传几百 MB 历史日志时偶发 `code=50300` +「文件读取失败: xxx」；重传往往就成功了。

**根因**：`MultipartFile` **不是文件本身，而是 Servlet 容器临时文件的句柄**。而导入是异步的——`startImport` 提交任务后立即返回 taskId、HTTP 请求随之结束，**容器在请求结束时清理那些临时文件**。后台线程真正去读的时刻往往已在清理之后：

```
主线程：存临时文件 → 把句柄交给线程池 → return taskId →【请求结束 → 容器删文件】
异步线程：                                  ↑ 此刻才 getInputStream() → 货没了
```

多数时候线程跑得够快就侥幸成功，所以长期没被发现。触发概率随**文件更大、并发更多、磁盘更慢**上升。

**修法**：引入「接管」这一步 —— **趁请求还活着，同步把数据复制进应用自己的暂存目录**，之后 service 只拿普通路径。

| 新增 | 作用 |
|---|---|
| [UploadStaging](src/main/java/org/example/tracemind/service/UploadStaging.java) | 全项目唯一的 `MultipartFile` 持有者；负责复制落盘、清理、路径穿越防护 |
| [LogSource](src/main/java/org/example/tracemind/pojo/dto/LogSource.java) | `record(path, displayName, size)`，与 Web 无关的来源值对象 |
| [ImportService](src/main/java/org/example/tracemind/service/ImportService.java) | 补上此前缺失的接口（`ImportServiceImpl` 曾是全项目唯一没有接口的 impl，controller 因此绑死实现类） |

配置项 `tracemind.upload.staging-dir`（默认 `./upload-staging`）。

**三个连带要点，改这块代码前务必知道**：

1. **清理责任从容器转到了应用**。每次导入最多 500MB，忘删就是磁盘泄漏。所以 `cleanup()` 同时出现在两处且**不可重叠**：`runImport` 的 `finally`（任务跑完清）、`startImport` 的 catch（**没成功提交异步任务时**清）。若在任务已启动后由上层再清一次，会把正在读的文件删掉 —— 那会把一次正常导入变成失败。
2. **物理文件名带序号前缀**（`0-app.log`）。浏览器选文件夹时 `app.log` 与 `backup/app.log` 同名会互相覆盖，第二个直接抹掉第一个的数据。展示名仍用原始名，且 **ES 文档 ID 必须用展示名**（`path.getFileName()` 已被加过前缀）。
3. **`getOriginalFilename()` 是客户端提供的字符串**，可能形如 `../../windows/system32/config/sam`。`sanitize()` 压掉路径分隔符与 `..`，否则写入会逃出暂存目录（路径穿越）。

**代价**：暂存多占一份磁盘。未采用 `transferTo()` —— 它要求目标不存在且为绝对路径、各版本行为不一致，内存型 multipart 下也省不到空间却多一套分支。

**顺带收益**：service 层不再出现 Web 类型，于是能脱离 Servlet 容器测试，也为"导入服务器本地已有日志"这类非 HTTP 来源留了口子。

---

### 其余已修复（2026-10-22 ～ 10-23）

| 原问题 | 修复方式 |
|---|---|
| Redis 不可用时监控三接口与删除项目返回 50000 | 抽出 `CollectorOffsetStore` 统一收口位点读写，异常时回落 MySQL 快照并标 `positionDegraded` |
| 采集行号错位覆盖 ES | offset 与 linesRead 存进同一条 Redis Hash 原子推进；采集器构造时回读初值。**注意 key 格式已变**，见常见坑 9 |
| **`bulkInsert` 吞掉单条写入错误** | 改为返回 `BulkResult(succeeded, failed)` 逐条回报；整批故障（IOException）仍抛异常，两者语义严格区分 |
| **兜底重放部分失败误删文件**（永久丢数据） | 只有全成功才删；部分失败用剩余原文**原子重写**文件（先写 `.tmp` 再 move），并收敛到只剩真失败的条目 |
| **一行坏 JSON 卡死整个兜底文件** | 逐行 try/catch 隔离：坏行移入 `corrupt/` 留档，同文件正常行照常被回灌 |
| 毒消息无限重试占资源 | 重试次数编码进文件名（`.attemptN.jsonl`），超 `max-replay-attempts`（默认 10）移入 `dead/` 并告警 |
| 采集侧重试整批、落盘整批 | `pending` 逐轮收敛，只重试、只落盘失败子集，不再重复投已成功的条目 |
| 导入把失败的行计入进度并标 DONE | `flushBatch` 返回**真正写入的行数**；一条都没写进去才抛异常 |
| `ws:ticket:` 两处字面量可致 WS 静默鉴权失败 | 新建 `RedisKeys` 统一命名（父前缀 `tracemind:`），签发与校验共用同一个方法 |
| Redis 位点 key 无 TTL 且无人清理 | 删除项目时调用 `offsetStore.clear()`（同时清位点 Hash 与指纹）；并修正了那句声称"有 TTL 兜底"的错误注释 |
| WS ticket 签发裸调 Redis | 鉴权关闭时不碰 Redis；开启时失败明确报 50300 而非 50000 |
| seq 计数器清理失败阻断诊断 | 改为记日志继续，seq 发号处本就有兜底 |
| `degraded` 一个布尔混用两种故障 | 拆为 `aiDegraded`（Python 熔断）+ `positionDegraded`（Redis 位点降级），Java / 前端 types / openapi 三端同步 |
| Python middleware 同名方法覆盖 | `TokenUsageMiddleware.after_model` 只保留同步版；token 统计走 `record()` |
| **导入的失效句柄（R-4.2）** | controller 在请求线程内把上传内容接管到应用暂存目录，service 只收 `List<LogSource>`；详见上方 🔴 专节 |
| **ES 文档 ID 三份实现** | 统一为 `{projectId}:{裸文件名}:{行号}`，生成与解析都收进 `es/EsDocId`。采集侧传绝对路径也会被归一，两条链路不再分叉；顺带消除 Windows 盘符冒号污染分隔符的隐患 |
| **WS handler 用 static 字段绕开注入（R-4.1）** | 迁移到 Spring `TextWebSocketHandler`：static 全删、依赖 final 构造注入、Origin 校验挪到 `WsHandshakeInterceptor`。鉴权与白名单同时改为 **fail-close**（拿不到配置就拒绝，不再放行） |
| **心跳清理失效连接永远清不掉** | 迁移时发现 handler 写 attributes 键 `diagSessionId`、Registry 读 `sessionId`，两边对不上。已统一为共享常量 `DiagnosisWsHandler.ATTR_SESSION_ID` |
| **`contextLoads` 冒烟测试恒失败** | 根因是 `ServerEndpointExporter` 需要 JSR-356 `ServerContainer`；随 WS 迁移一起消失 |
| `diagnosis` 包与 service 双向依赖 | 拆出 `DiagnosisEventPipeline`（发号→落库→推送）与 `DiagnosisEventPublisher` 端口，业务侧不再持有 `jakarta.websocket.Session` |
| collector 12 参手抄依赖 | 抽 `CollectorDependencies` + `CollectorFactory`；位点恢复从构造器移到 `init()`，由工厂保证不漏（有测试锁住） |
| `ImportServiceImpl` 没有接口 | 补 `ImportService`，controller 不再绑死实现类；同时把手写构造器的 `@Qualifier` 补齐 |
| **追问的 `SseEmitter` 写在 service 接口上（R-4.3）** | 新增 `StreamSink`（emit/done/fail）回调端口：接口去 Web 类型、controller 包 SSE 适配器，**对外契约未变**。顺带修掉两个真问题：① Python 流不带 `[DONE]` 而自然耗尽时的**双重终止**（第二次 send 打在已 complete 的 emitter 上抛异常）；② 线程池拒绝提交时 sink 无人通知、**客户端挂到超时**。另 `fail()` 改为把原因作为一帧送出而非 `completeWithError`（后者响应头已发出只能回空体，前端读不到原因） |
| **ES 仓储层以 VO 作出入参（R-4.4）** | 新增 `AggregateResult`（中性聚合事实）+ `LogVoConverter`（Doc→VO、聚合→图表结构的唯一出口）。`search/getById/aggregate` 三处签名去 VO 化，5 个调用点由编译器全部揪出并适配；`PageResult.map()` 收拢转换样板。验收：`es` 包内除转换器外无任何 `pojo.vo` import，与 `mapper` 包对齐。⚠️ 复核修正：初版把 `extendedBounds` 的 +08:00 也当成"仓储做视图"，实际那是聚合正确性的必要条件，应留在仓储层 |
| 线程池依赖两种写法 | `ImportServiceImpl` 从具体 `ThreadPoolTaskExecutor` 改为 `Executor` 抽象，与 `DiagnosisServiceImpl` 对齐（测试因此简化很多） |
| **`rootMessage()` 两份实现，遇异常成环会死循环** | 收口到 `common/Utilx`，限步数 20。⚠️ 复核修正：此前以为"一份有防护一份没有"，实际**两份都不完整**——那份"有防护"的只挡自引用，挡不住 A→B→A 两元素环 |
| `toInstant(LocalDateTime)` 四份逐字节副本 + 2 处内联 | 统一委托 `Utilx.toInstant` / `Utilx.toIsoUtc`，UTC 假设只有一个出口 |
| `config` 包混入运行时业务组件（依赖方向反了） | `CollectorOffsetStore`→`collector`、`AutoDiagnosisSwitch`→`diagnosis`、两个启动钩子→新增 `bootstrap` 包；`config` 只剩 6 个真装配类 |

> ⚠️ 关于「三层写入容灾」的严重性，本文早期版本曾把 `bulkInsert` 那条列为"最严重、正在丢数据"。
> 复核后下调：**在当前 schema 下采集侧极难触发**（字段全部由代码生成、`rawText` 是 text 类型容得下任意字符串、
> `level` 归一化成枚举名），真正会走"部分失败"分支的是集群过载／索引被锁这类事件。
> 但**兜底重放侧风险中等**——它处理的是几周前落盘的旧数据，期间 mapping 若收紧过就必然撞上。
> 两个方向都已修，此处记录定级过程以免重犯。

---

## License

个人项目，未指定开源协议。
