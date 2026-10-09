"""重试与超时。

**只用于幂等的、可重试的操作**（读 ES、调 LLM）。
不要在写 MySQL 的事务里用 —— 那会产生重复写入。
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from typing import TypeVar

from tenacity import (
    AsyncRetrying,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential_jitter,
)

from trace_mind_ai.core.logging import get_logger

T = TypeVar("T")
log = get_logger(__name__)


async def call_with_retry(
    fn: Callable[[], Awaitable[T]],
    *,
    attempts: int = 3,
    base_delay: float = 0.5,
    max_delay: float = 8.0,
    timeout_s: float | None = None,
    operation: str = "operation",
    default: T | None = None,
    retry_on: tuple[type[BaseException], ...] = (Exception,),
) -> T:
    """带退避重试 + 超时的调用。

    参数 default 是**降级返回值**：传了它，全部重试失败后返回默认值而不是抛异常。
    这是本工程里「依赖故障不中断主链路」的统一实现方式。

    退避策略用 `wait_exponential_jitter`：
      纯指数退避（1s/2s/4s）在多个实例同时失败时会产生**惊群** ——
      大家在同一时刻一起重试。加抖动后重试时间点被打散。
    """
    try:
        async for attempt in AsyncRetrying(
            stop=stop_after_attempt(attempts),
            wait=wait_exponential_jitter(initial=base_delay, max=max_delay),
            retry=retry_if_exception_type(retry_on),
            reraise=True,
        ):
            with attempt:
                call = fn()
                if timeout_s is not None:
                    call = asyncio.wait_for(call, timeout=timeout_s)
                return await call
    except Exception as exc:
        if default is not None:
            log.warning(
                "retry.exhausted_fallback",
                operation=operation,
                attempts=attempts,
                error=type(exc).__name__,
                detail=str(exc)[:200],
            )
            return default
        log.error("retry.exhausted", operation=operation, attempts=attempts, error=str(exc)[:200])
        raise

    # 理论上不可达（AsyncRetrying 要么 return 要么 raise）
    raise RuntimeError(f"{operation}: 重试循环异常退出")