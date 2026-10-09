"""MySQL 客户端 —— 只读元数据。

为什么需要它：`/v1/chat` 只收到 {sessionId, question, history}，
**没有 projectId**。要回答"这条追问是关于哪个项目的"，
必须拿 sessionId 反查 agent_session 表。

⚠️ 日志正文不在 MySQL（在 ES），所以这里查的都是小表，不会成为瓶颈。
"""

from __future__ import annotations

from typing import Any

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, async_sessionmaker, create_async_engine

from trace_mind_ai.core.config import get_settings
from trace_mind_ai.core.logging import get_logger

log = get_logger(__name__)

_engine: AsyncEngine | None = None
_sessionmaker: async_sessionmaker | None = None


def get_engine() -> AsyncEngine:
    global _engine, _sessionmaker
    if _engine is None:
        settings = get_settings().mysql
        _engine = create_async_engine(
            settings.dsn,
            echo=settings.echo,
            pool_size=settings.pool_size,
            max_overflow=settings.max_overflow,
            # ★ 连接活性检查：MySQL 的 wait_timeout 默认 8 小时，
            #   连接被服务端单向断开后，客户端手里的连接是"死的"，
            #   不检查就会在下次使用时才报错。pre_ping 让 SQLAlchemy 提前发现并重建
            pool_pre_ping=True,
            pool_recycle=1800,
        )
        _sessionmaker = async_sessionmaker(_engine, expire_on_commit=False)
        log.info("mysql.engine_created")
    return _engine


async def close_engine() -> None:
    global _engine, _sessionmaker
    if _engine is not None:
        await _engine.dispose()
        _engine = None
        _sessionmaker = None
        log.info("mysql.engine_closed")


async def ping() -> tuple[bool, str | None]:
    try:
        async with get_engine().connect() as conn:
            await conn.execute(text("SELECT 1"))
        return True, None
    except Exception as exc:
        return False, f"{type(exc).__name__}: {str(exc)[:160]}"


async def fetch_one(sql: str, params: dict[str, Any]) -> dict[str, Any] | None:
    """查一行。失败返回 None（降级，不抛）。"""
    try:
        async with get_engine().connect() as conn:
            result = await conn.execute(text(sql), params)
            row = result.mappings().first()
            return dict(row) if row else None
    except Exception as exc:
        log.warning("mysql.query_failed", error=type(exc).__name__, detail=str(exc)[:200])
        return None


async def fetch_all(sql: str, params: dict[str, Any]) -> list[dict[str, Any]]:
    try:
        async with get_engine().connect() as conn:
            result = await conn.execute(text(sql), params)
            return [dict(r) for r in result.mappings().all()]
    except Exception as exc:
        log.warning("mysql.query_failed", error=type(exc).__name__)
        return []


# ══════════════════════════════════════════════════════════
# 具体查询
# ══════════════════════════════════════════════════════════

_GET_SESSION_SQL = """
SELECT session_id   AS sessionId,
       project_id   AS projectId,
       trigger_log_id AS triggerLogId,
       status,
       root_cause   AS rootCause,
       suggestion
FROM agent_session
WHERE session_id = :session_id
LIMIT 1
"""


async def get_session(session_id: str) -> dict[str, Any] | None:
    """按 sessionId 查诊断会话。chat 接口靠它拿 projectId。"""
    return await fetch_one(_GET_SESSION_SQL, {"session_id": session_id})


_GET_PROJECT_SQL = """
SELECT id, name, source, log_path AS logPath, language, status
FROM project
WHERE id = :project_id
LIMIT 1
"""


async def get_project(project_id: str) -> dict[str, Any] | None:
    return await fetch_one(_GET_PROJECT_SQL, {"project_id": project_id})


_MONITOR_TASKS_SQL = """
SELECT task_id AS taskId, project_id AS projectId, status,
       degraded, error_count AS errorCount
FROM log_monitor_task
WHERE project_id = :project_id
"""


async def get_monitor_tasks(project_id: str) -> list[dict[str, Any]]:
    """采集任务状态 —— 给 check_project_health 工具用。

    ⚠️ degraded 这一列在真库里有（手动 ALTER 加的），但 schema.sql 里没有。
    如果换了新环境建库，这里会报 Unknown column。
    修法：把 degraded 补进 resources/db/schema.sql。
    """
    return await fetch_all(_MONITOR_TASKS_SQL, {"project_id": project_id})