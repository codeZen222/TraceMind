"""Redis 客户端。

本服务用 Redis 只做两件事（都不是关键路径）：
  ① 相邻日志的临时缓存（可选优化）
  ② 模板模型的元信息
★ 注意：**seq 发号是 Java 侧的职责**（agent_event 表的 seq 由 Java 用
  Redis INCR 生成并落库）。Python 不参与发号 —— 那是为了「先落库后推送」
  的强顺序保证，必须和落库在同一侧完成。
"""

from __future__ import annotations

import redis.asyncio as aioredis

from trace_mind_ai.core.config import get_settings
from trace_mind_ai.core.logging import get_logger

log = get_logger(__name__)

_client: aioredis.Redis | None = None


def get_client() -> aioredis.Redis:
    global _client
    if _client is None:
        settings = get_settings().redis
        _client = aioredis.from_url(
            settings.url,
            socket_timeout=settings.socket_timeout_s,
            decode_responses=True,
            health_check_interval=30,
        )
        log.info("redis.client_created")
    return _client


async def close_client() -> None:
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None
        log.info("redis.client_closed")


async def ping() -> tuple[bool, str | None]:
    try:
        return (await get_client().ping()) is True, None
    except Exception as exc:
        return False, f"{type(exc).__name__}: {str(exc)[:160]}"