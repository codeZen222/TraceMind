"""GET /v1/health —— 探活。

★ 这个接口有个容易做错的地方：
  Java 的 PythonClient.extractLlmOk() 会读响应里的 `llm.ok`，
  用它决定前端显示的"AI 可用"状态。

  **绝不能返回写死的 true。** 报假的好消息比报错更糟 ——
  用户会以为 AI 可用，点了诊断才发现不行。

  所以这里做真实探测（probe_llm 内部有 30 秒缓存，不会真的每次调模型）。
"""

from __future__ import annotations

import asyncio
import time
from typing import Annotated, Any

from fastapi import APIRouter, Query

from trace_mind_ai import __version__
from trace_mind_ai.core.config import get_settings
from trace_mind_ai.core.llm import probe_llm
from trace_mind_ai.infra import es, mysql, redis, vectorstore

router = APIRouter(tags=["health"])

_STARTED_AT = time.monotonic()


@router.get("/health")
async def health(
    deep: Annotated[bool, Query(description="是否深度探测 LLM（会真的调用模型）")] = True,
) -> dict[str, Any]:
    """逐项探测并返回明细。

    用 asyncio.gather 并发探测：串行的话四个依赖各超时 5 秒 = 20 秒，
    而探活接口本身应该在秒级返回。
    """
    settings = get_settings()

    es_task = asyncio.wait_for(es.ping(), timeout=6.0)
    redis_task = asyncio.wait_for(redis.ping(), timeout=6.0)
    mysql_task = asyncio.wait_for(mysql.ping(), timeout=6.0)
    llm_task = probe_llm(force=deep)

    results = await asyncio.gather(
        es_task, redis_task, mysql_task, llm_task, return_exceptions=True
    )
    es_result, redis_result, mysql_result, llm_result = results

    dependencies: dict[str, Any] = {}
    for name, result in (
        ("elasticsearch", es_result),
        ("redis", redis_result),
        ("mysql", mysql_result),
    ):
        if isinstance(result, BaseException):
            dependencies[name] = {"ok": False, "detail": f"{type(result).__name__}"}
        else:
            ok, detail = result      # type: ignore[misc]
            dependencies[name] = {"ok": ok, "detail": detail}

    # LLM 的形状必须与 Java 的 extractLlmOk() 约定一致：llm.ok 是布尔
    if isinstance(llm_result, BaseException):
        llm_block: dict[str, Any] = {
            "ok": False,
            "model": settings.llm.model,
            "detail": type(llm_result).__name__,
        }
    else:
        llm_block = {
            "ok": llm_result.ok,
            "model": llm_result.model,
            "latencyMs": llm_result.latencyMs,
        }
        if llm_result.detail:
            llm_block["detail"] = llm_result.detail

    # 核心依赖（LLM）不可用 → status 为 degraded，Java 侧可据此决定要不要熔断
    status = "ok" if llm_block["ok"] else "degraded"

    return {
        "status": status,
        "service": "trace-mind-ai",
        "version": __version__,
        "uptimeSeconds": int(time.monotonic() - _STARTED_AT),
        "environment": settings.environment,
        "llm": llm_block,
        "dependencies": dependencies,
        "cases": await vectorstore.count(),
    }