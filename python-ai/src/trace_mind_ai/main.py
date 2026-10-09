"""FastAPI 应用工厂。

★ 启动顺序有讲究（错了会出现"日志没配好"或"连接没预热"）：
    ① 配置校验（fail fast —— 配错了立刻退出，别等第一个请求）
    ② 日志配置（之后所有代码都能打日志）
    ③ 客户端预热（避免第一个请求承担建连 + 模型加载成本）
    ④ 挂路由
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from trace_mind_ai import __version__
from trace_mind_ai.api.errors import register_exception_handlers
from trace_mind_ai.api.middleware import TraceMiddleware
from trace_mind_ai.api.v1.router import api_router
from trace_mind_ai.core.config import get_settings
from trace_mind_ai.core.logging import configure_logging, get_logger
from trace_mind_ai.infra import es, mysql, redis, vectorstore

log = get_logger(__name__)


async def _warm_up() -> None:
    """预热外部连接。

    ★ 为什么值得做：
      · Elasticsearch 的首次请求要建连接 + 发现集群拓扑，冷启动能到几百毫秒
      · Chroma 首次查询要加载 embedding 模型（几秒）
      不做预热的话，**第一个真实用户会承担这些成本**，
      而他恰好是最可能被你用来演示的那次请求。

    ★ 为什么各自加超时且不抛异常：
      ES / Redis / Chroma 都是**可降级**依赖，不通不该阻止服务启动。
      MySQL 也放宽容 —— 它在 /v1/chat 里才用到。
      （配置格式错会在 get_settings() 就炸，那是另一回事。）
    """

    async def probe(name: str, coro: object) -> None:
        try:
            ok, detail = await asyncio.wait_for(coro, timeout=5.0)  # type: ignore[misc]
            if ok:
                log.info("warmup.ok", dependency=name)
            else:
                log.warning("warmup.unavailable", dependency=name, detail=detail)
        except Exception as exc:
            log.warning("warmup.failed", dependency=name, error=type(exc).__name__)

    await probe("elasticsearch", es.ping())
    await probe("redis", redis.ping())
    await probe("mysql", mysql.ping())

    # Chroma 单独处理：它的初始化是同步的且可能很慢
    try:
        count = await asyncio.wait_for(vectorstore.count(), timeout=20.0)
        log.info("warmup.ok", dependency="chroma", cases=count)
    except Exception as exc:
        log.warning("warmup.failed", dependency="chroma", error=type(exc).__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:  # noqa: ARG001
    settings = get_settings()
    configure_logging(settings.log.level, settings.log.format)

    log.info(
        "service.starting",
        version=__version__,
        environment=settings.environment,
        model=settings.llm.model,
        llm_base_url=settings.llm.base_url,
    )

    await _warm_up()

    log.info("service.ready", host=settings.server.host, port=settings.server.port)
    try:
        yield
    finally:
        log.info("service.stopping")
        # ★ 必须关：不关的话连接池会挂着，
        #   容器编排下频繁重启会让下游看到大量半开连接
        await es.close_client()
        await redis.close_client()
        await mysql.close_engine()
        log.info("service.stopped")


def create_app() -> FastAPI:
    settings = get_settings()

    app = FastAPI(
        title="TraceMind AI Service",
        description="日志诊断推理服务。Java 侧通过 HTTP + SSE 调用，前端不直连。",
        version=__version__,
        lifespan=lifespan,
        # ★ 生产关掉文档：它会把全部接口结构暴露出去。
        #   /docs /redoc /openapi.json 三个入口都要关
        docs_url=None if settings.is_production else "/docs",
        redoc_url=None if settings.is_production else "/redoc",
        openapi_url=None if settings.is_production else "/openapi.json",
    )

    app.add_middleware(TraceMiddleware)
    register_exception_handlers(app)
    app.include_router(api_router, prefix="/v1")

    return app


#: uvicorn 的入口点。`uvicorn trace_mind_ai.main:app`
app = create_app()