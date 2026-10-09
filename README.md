# TraceMind — 智能日志诊断平台

TraceMind 是一款基于 AI 的日志采集、分析与诊断平台。它面向**多项目、多租户**场景，提供从日志接入 → 实时监控 → 错误自动发现 → AI 驱动诊断 → 诊断报告生成的完整闭环。

## ✨ 核心功能

| 功能 | 说明 |
|------|------|
| 📥 日志接入 | 支持文件监听（WatchService 实时采集）和手动上传两种方式 |
| 🔍 日志检索 | 基于 Elasticsearch 的全文搜索，支持级别、时间、关键字等多维度过滤 |
| 🛰️ 实时监控 | 按项目配置监听周期，**错误雷达**自动发现新类型 ERROR 并触发诊断 |
| 🧠 AI 诊断 | 三阶段诊断流水线：模板提取 → 规则匹配 → LLM Agent 深度分析 |
| 💬 AI 对话 | 在诊断基础上追加交互式提问，Agent 调用工具链自主排查 |
| 📊 诊断报告 | 每次诊断自动生成 Markdown 报告，含根因分析、修复建议和置信度 |
| 👥 多租户 | JWT 认证 + 项目级权限隔离，每个用户只能访问自己的项目 |
| 🔗 实时推送 | WebSocket 连接诊断过程实时可见：阶段进度、工具调用、Token 消耗一目了然 |

## 🏗️ 架构总览

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────────────┐
│   Frontend  │────▶│   Backend (Java) │────▶│  Python AI Service  │
│  Vue 3 +    │◀───▶│  Spring Boot 3.4 │◀───▶│  FastAPI + LangChain│
│  Element Plus│ WS  │  MyBatis + JWT   │ HTTP │  Drain3 + ChromaDB  │
└─────────────┘     └───┬────┬────┬────┘     └──────────┬──────────┘
                         │    │    │                     │
                   ┌─────┘    │    └─────┐          ┌────┘
                   ▼          ▼          ▼          ▼
               ┌──────┐ ┌───────┐ ┌──────────┐ ┌──────────┐
               │MySQL │ │Redis  │ │Elasticsearch│ │ChromaDB │
               │ 元数据│ │ 计数器 │  日志正文  │ │ 向量检索 │
               └──────┘ └───────┘ └──────────┘ └──────────┘
```

### 🧠 AI 诊断流水线

每次诊断经历三个阶段的层层递进，**先省后花**：

```
阶段 1: 模板提取（Drain3）          → 0 Token，日志归类
阶段 2: 规则匹配（5 个内置分析器）   → 0 Token，命中直接出结论
阶段 3: Agent 深度分析（LangChain）  → 调 LLM，仅前两阶段未命中时触发
```

**内置规则分析器**：空指针异常 / SQL 错误 / 连接拒绝 / 超时 / OOM

**Agent 工具链**：日志搜索 / 异常规则 / 错误频率 / 项目健康度 / 相似历史案例 / 源码上下文

## 🚀 快速开始

### 前置要求

- **Docker & Docker Compose**（一键部署）
- 本地开发额外需要：Java 17+ / Python 3.11~3.12 / Node.js 22+ / pnpm

### 一键部署

```bash
# 1. 克隆
git clone https://github.com/your-username/TraceMind.git
cd TraceMind

# 2. 配置 LLM API Key（必填）
cp python-ai/.env.example python-ai/.env
# 编辑 python-ai/.env，填入你的 LLM_API_KEY

# 3. 启动全部服务（首次拉镜像需几分钟）
docker compose up -d

# 4. 访问
#    前端:       http://localhost
#    后端 API:   http://localhost:8080
#    Python AI:  http://localhost:8100/docs
```

### 本地开发

#### 1. 启动基础设施

```bash
docker compose up -d mysql redis elasticsearch
```

#### 2. 后端 (Java 17 + Spring Boot)

```bash
cd backend
./mvnw spring-boot:run
```

#### 3. Python AI 服务

```bash
cd python-ai
python -m venv .venv
source .venv/bin/activate       # Windows: .venv\Scripts\activate
pip install -r requirements/base.txt
cp .env.example .env            # 编辑填入 LLM_API_KEY
python -m trace_mind_ai
```

#### 4. 前端 (Vue 3 + Vite)

```bash
cd frontend
pnpm install
pnpm dev
```

## 📂 项目结构

```
TraceMind/
├── backend/                # Java 后端服务
│   ├── src/main/java/.../tracemind/
│   │   ├── controller/     # REST API 控制器
│   │   ├── service/        # 业务逻辑层
│   │   ├── collector/      # 日志采集（WatchService）
│   │   ├── diagnosis/      # WebSocket 诊断推送
│   │   ├── es/             # Elasticsearch 仓储
│   │   ├── security/       # JWT 认证与鉴权
│   │   └── config/         # 配置类
│   └── src/main/resources/
│       ├── application.yml # 主配置
│       └── db/schema.sql   # 数据库初始化脚本
│
├── python-ai/              # Python AI 推理服务
│   └── src/trace_mind_ai/
│       ├── agents/         # 诊断 Agent + 对话 Agent + Prompt
│       ├── api/v1/         # FastAPI 路由（health/parse/diagnose/chat）
│       ├── domain/         # 诊断流水线 + Drain3 模板 + 规则引擎
│       │   └── rules/analyzers/  # 5 个内置异常分析器
│       ├── tools/          # Agent 工具（搜日志/查规则/频率/向量相似）
│       ├── infra/          # ES / Redis / MySQL / ChromaDB 客户端
│       ├── core/           # 配置 / LLM / 日志 / 异常
│       └── schemas/        # Pydantic 数据模型
│
├── frontend/               # Vue 3 前端
│   └── src/
│       ├── views/          # 页面视图
│       │   ├── projects/   # 项目管理 / 日志检索 / 监控
│       │   ├── diagnosis/  # AI 诊断面板
│       │   └── reports/    # 报告中心
│       ├── components/     # 公共组件 + 诊断专用组件
│       ├── api/            # 后端 API 封装
│       ├── stores/         # Pinia 状态管理
│       └── router/         # 路由 + 登录守卫
│
├── docker-compose.yml      # 一键编排全部服务
└── .env.example            # 环境变量模板
```

### 技术栈速览

| 层级 | 技术 |
|------|------|
| 前端 | Vue 3, Vite 5, TypeScript, Element Plus, ECharts, Pinia, Markdown-it |
| 后端 | Spring Boot 3.4, MyBatis 3, Spring Security, JWT (jjwt), WebSocket |
| AI 服务 | FastAPI, LangChain 1.x (LangGraph), Drain3, ChromaDB |
| 存储 | MySQL 8, Redis 7, Elasticsearch 8, ChromaDB |
| 运维 | Docker Compose, Nginx |

## 🔧 环境变量

所有敏感配置均通过环境变量注入，**绝不硬编码**。

| 变量 | 说明 | 所属服务 |
|------|------|----------|
| `LLM_API_KEY` | 大模型 API Key（必填） | python-ai |
| `LLM_BASE_URL` | 模型 API 地址（兼容 OpenAI 协议） | python-ai |
| `LLM_MODEL` | 模型名称 | python-ai |
| `DB_PASSWORD` | MySQL 密码 | backend / python-ai |
| `REDIS_PASSWORD` | Redis 密码 | backend / python-ai |
| `JWT_SECRET` | JWT 签名密钥（至少 32 字符） | backend |
| `ES_HOSTS` | Elasticsearch 地址 | python-ai |
| `MYSQL_DSN` | MySQL 连接串（SQLAlchemy 异步格式） | python-ai |
| `REDIS_URL` | Redis 连接串 | python-ai |

详细说明见 `python-ai/.env.example`。

