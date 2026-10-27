# ── 构建阶段 ──
FROM python:3.12-slim AS build
WORKDIR /app

# 安装编译工具（asyncmy / chromadb 需要 libssl-dev）
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

# 先拷依赖定义，利用 Docker 层缓存
COPY requirements/ ./requirements/
RUN pip install --no-cache-dir -r requirements/base.txt

# ── 运行阶段 ──
FROM python:3.12-slim
WORKDIR /app

# asyncmy 运行时需要 libssl3
RUN apt-get update && apt-get install -y --no-install-recommends \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

# 从构建阶段复制已安装的包
COPY --from=build /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages
COPY --from=build /usr/local/bin /usr/local/bin

# 拷贝源码
COPY src/ ./src/
COPY pyproject.toml ./

# 非 root 运行
RUN addgroup --system app && adduser --system --ingroup app app
USER app

EXPOSE 8100

# ⚠️ 路径必须是 /v1/health —— FastAPI 的路由整体挂在 prefix="/v1" 下（见 main.py），
#   写成 /health 会 404、urlopen 抛 HTTPError、容器恒 unhealthy。
#   而 docker-compose.yml 里 backend 依赖 python-ai 的 condition: service_healthy，
#   一旦这里判不健康，后端就永远不会被启动 —— 整条链路卡死在编排层。
#
# 用 python 标准库而不是 curl/wget：slim 镜像里两者都不保证存在，
# 而解释器本来就要用来跑服务，一定可用。
#
# 不加 ?deep=false 是有意的：默认 deep=true 时 LLM 探测有 30 秒缓存，
# 且即使 LLM 不可用，接口仍返回 HTTP 200（body 里 status=degraded）——
# urlopen 只看状态码，所以这里探的是"服务能否响应"，不会被模型故障连累。
HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8100/v1/health')" || exit 1

CMD ["python", "-m", "trace_mind_ai"]