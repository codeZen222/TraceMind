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

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8100/health')"

CMD ["python", "-m", "trace_mind_ai"]