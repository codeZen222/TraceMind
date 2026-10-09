"""结构化日志。

开发用彩色控制台，生产用 JSON（Loki / ELK 能直接解析）。
所有日志自动携带 trace_id —— 就是 Java 传来的 sessionId。
"""

from __future__ import annotations

import logging
import re
import sys
from typing import Any

import structlog
from structlog.contextvars import bind_contextvars, clear_contextvars

#: 长得像 API Key 的串。只保留前 6 位便于定位是哪个 Key，后面全打码。
_SECRET_PATTERN = re.compile(r"(sk-[A-Za-z0-9]{6})[A-Za-z0-9\-_]{4,}")

#: 这些第三方 logger 太吵，降级
_NOISY_LOGGERS = (
    "uvicorn.access",
    "elastic_transport.transport",
    "httpx",
    "httpcore",
    "chromadb",
    "urllib3",
)


def _scrub_secrets(_logger: Any, _method: str, event_dict: dict[str, Any]) -> dict[str, Any]:
    """脱敏 processor。

    为什么必须做：调试时很自然会 `log.info("request", headers=req.headers)`，
    而 headers 里就带着 Authorization。**一次手滑，Key 就进了日志文件。**
    """
    for key, value in list(event_dict.items()):
        if isinstance(value, str):
            event_dict[key] = _SECRET_PATTERN.sub(r"\1***", value)
    return event_dict


def bind_trace(session_id: str | None, **extra: Any) -> None:
    """绑定 trace 上下文。之后所有日志自动带上。

    用 contextvars（structlog 内部实现），不用全局变量 ——
    并发处理多个诊断时，每个请求的 trace_id 互不串。
    """
    clear_contextvars()
    payload: dict[str, Any] = dict(extra)
    if session_id:
        payload["trace_id"] = session_id
    if payload:
        bind_contextvars(**payload)


def clear_trace() -> None:
    clear_contextvars()


def get_logger(name: str | None = None) -> structlog.stdlib.BoundLogger:
    return structlog.get_logger(name)  # type: ignore[no-any-return]


def configure_logging(level: str = "INFO", fmt: str = "console") -> None:
    """一次性配置。在应用启动的最早时机调用（main.py 的模块顶部就调）。"""
    numeric_level = getattr(logging, level.upper(), logging.INFO)

    shared_processors: list[Any] = [
        # merge_contextvars 放第一个：让 bind_trace 绑的值能进到每条日志
        structlog.contextvars.merge_contextvars,
        structlog.processors.add_log_level,
        structlog.processors.StackInfoRenderer(),
        # 把异常渲染进日志（否则 exception 只是个 repr）
        structlog.processors.format_exc_info,
        structlog.processors.TimeStamper(fmt="iso", utc=True),
        _scrub_secrets,
    ]

    if fmt == "json":
        renderer: Any = structlog.processors.JSONRenderer()
    else:
        renderer = structlog.dev.ConsoleRenderer(colors=True)

    structlog.configure(
        processors=[*shared_processors, renderer],
        wrapper_class=structlog.make_filtering_bound_logger(numeric_level),
        logger_factory=structlog.PrintLoggerFactory(file=sys.stdout),
        # 生产环境必须 True：只做一次配置查找，省掉每次调用的开销
        cache_logger_on_first_use=True,
    )

    # 标准库 logging 也统一走 stdout（uvicorn / sqlalchemy 用的是它）
    logging.basicConfig(
        format="%(message)s",
        stream=sys.stdout,
        level=numeric_level,
        force=True,          # 覆盖已有 handler（uvicorn 自己会配一个）
    )
    for name in _NOISY_LOGGERS:
        logging.getLogger(name).setLevel(logging.WARNING)