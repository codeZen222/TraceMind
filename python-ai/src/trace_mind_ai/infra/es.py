"""Elasticsearch 客户端 —— 日志检索。

三条必须守住的规矩：
① **所有查询强制带 project_id 过滤**。业务数据隔离，不是可选项。
   漏了这个 = 任何人都能查别人项目的日志。
② 客户端单例。AsyncElasticsearch 内部有连接池，每次 new 会泄漏。
③ `timestamp` 字段类型以 Java 侧 LogEntryDoc 的 mapping 为准。
   本实现按 **date** 类型写（Java 存 ISO-8601 字符串，ES 默认会识别成 date）。
   如果实际 mapping 是 keyword，range 查询要改成 term + 格式化字符串。
"""

from __future__ import annotations

from typing import Any

from elasticsearch import AsyncElasticsearch, NotFoundError

from trace_mind_ai.core.config import get_settings
from trace_mind_ai.core.exceptions import DependencyUnavailable
from trace_mind_ai.core.logging import get_logger
from trace_mind_ai.schemas.log import LogEntry

log = get_logger(__name__)

_client: AsyncElasticsearch | None = None


def get_client() -> AsyncElasticsearch:
    """取 ES 客户端（进程级单例）。"""
    global _client
    if _client is None:
        settings = get_settings().es
        _client = AsyncElasticsearch(
            hosts=settings.host_list,
            request_timeout=settings.request_timeout_s,
            # 重试交给上层统一控制（core.retry），这里关掉避免双重重试导致请求数翻倍
            max_retries=0,
            retry_on_timeout=False,
        )
        log.info("es.client_created", hosts=settings.host_list)
    return _client


async def close_client() -> None:
    """应用关闭时调用。不关的话连接池会挂着，容器重启时可能触发 ES 侧连接数告警。"""
    global _client
    if _client is not None:
        await _client.close()
        _client = None
        log.info("es.client_closed")


async def ping() -> tuple[bool, str | None]:
    """探活。返回 (是否可用, 失败原因)。"""
    try:
        return await get_client().ping(), None
    except Exception as exc:
        return False, f"{type(exc).__name__}: {str(exc)[:160]}"


async def search_logs(
    *,
    project_id: str,
    keyword: str | None = None,
    level: str | None = None,
    start_ts: str | None = None,
    end_ts: str | None = None,
    size: int = 20,
) -> list[LogEntry]:
    """检索日志。

    ★ project_id 是必填位置参数 —— 故意设计成"想忘都忘不掉"。
      如果给默认值，某个调用点漏传就会静默查出所有项目的数据。
    """
    index = get_settings().es.index_logs
    size = max(1, min(size, 200))     # 硬上限，防一次拉爆内存

    must: list[dict[str, Any]] = [{"term": {"projectId": project_id}}]

    if keyword:
        # multi_match 而不是 match：日志检索要同时覆盖正文和堆栈
        must.append({
            "multi_match": {
                "query": keyword,
                "fields": ["rawText^2", "stackTrace"],   # ^2 给正文更高权重
                "type": "best_fields",
            }
        })
    if level and level.upper() != "ALL":
        # ★ "ALL" 是前端的"全部"，不是合法枚举，必须当"不过滤"处理。
        #   这条规则在 Java 侧也踩过（见 MEMORY），Python 侧保持一致
        must.append({"term": {"level": level.upper()}})
    if start_ts or end_ts:
        rng: dict[str, str] = {}
        if start_ts:
            rng["gte"] = start_ts
        if end_ts:
            rng["lte"] = end_ts
        must.append({"range": {"timestamp": rng}})

    body = {
        "query": {"bool": {"must": must}},
        "sort": [{"timestamp": {"order": "desc"}}],
        "size": size,
        "_source": True,
    }

    try:
        response = await get_client().search(index=index, body=body)
    except NotFoundError:
        # 索引还不存在是**正常情况**（新项目、还没导入过日志）
        log.info("es.index_missing", index=index)
        return []
    except Exception as exc:
        # ★ 不抛异常。ES 挂了，诊断应该降级继续（Agent 会知道"检索不到日志"），
        #   而不是整条链路断掉
        log.warning("es.search_failed", error=type(exc).__name__, detail=str(exc)[:200])
        return []

    return [_to_entry(hit) for hit in response.get("hits", {}).get("hits", [])]


async def get_log_by_id(*, project_id: str, log_id: str) -> LogEntry | None:
    """按 ES 的 `_id` 精确取一条日志。

    ★ 为什么不复用 search_logs 再在内存里比对 id：
      search_logs 是"按时间倒序取前 N 条"，而触发日志的行号往往排在很靠后
      （比如第 676 行），**根本不在前 N 条里** —— 调用方拿到 None，
      还以为"这条日志不存在"，于是诊断上下文里悄悄少了一段关键信息。
      ES 的 get 是按 _id 直取，既准又快（不走倒排索引）。

    ★ project_id 依然强制校验：拿到文档后比对 projectId，不匹配就当不存在。
      知道 id 不等于有权限读 —— 跨项目读取必须挡住。
    """
    index = get_settings().es.index_logs
    try:
        response = await get_client().get(index=index, id=log_id)
    except NotFoundError:
        return None
    except Exception as exc:
        log.warning("es.get_by_id_failed", error=type(exc).__name__, detail=str(exc)[:200])
        return None

    entry = _to_entry({"_source": response.get("_source", {}) or {}, "_id": response.get("_id")})
    if entry.projectId and entry.projectId != project_id:
        log.warning("es.get_by_id_cross_project", log_id=log_id)
        return None
    return entry


async def count_by_level(*, project_id: str, start_ts: str | None, end_ts: str | None) -> dict[str, int]:
    """按级别统计数量 —— 给 count_error_frequency 工具用。"""
    index = get_settings().es.index_logs
    must: list[dict[str, Any]] = [{"term": {"projectId": project_id}}]
    if start_ts or end_ts:
        rng: dict[str, str] = {}
        if start_ts:
            rng["gte"] = start_ts
        if end_ts:
            rng["lte"] = end_ts
        must.append({"range": {"timestamp": rng}})

    body = {
        "size": 0,          # 只要聚合结果，不要文档
        "query": {"bool": {"must": must}},
        "aggs": {"by_level": {"terms": {"field": "level", "size": 10}}},
    }

    try:
        response = await get_client().search(index=index, body=body)
    except Exception as exc:
        log.warning("es.agg_failed", error=type(exc).__name__)
        return {}

    buckets = response.get("aggregations", {}).get("by_level", {}).get("buckets", [])
    return {b["key"]: b["doc_count"] for b in buckets}


async def top_templates(*, project_id: str, size: int = 10) -> list[tuple[str, int]]:
    """出现最多的模板 —— 给 Agent 一个"这个项目最近在反复报什么"的视图。"""
    index = get_settings().es.index_logs
    body = {
        "size": 0,
        "query": {"bool": {"must": [{"term": {"projectId": project_id}}]}},
        "aggs": {
            "top_tpl": {
                "terms": {
                    "field": "template.keyword",   # ★ 模板文本要聚合，必须有 .keyword 子字段
                    "size": size,
                }
            }
        },
    }
    try:
        response = await get_client().search(index=index, body=body)
    except Exception as exc:
        log.warning("es.template_agg_failed", error=type(exc).__name__)
        return []

    buckets = response.get("aggregations", {}).get("top_tpl", {}).get("buckets", [])
    return [(b["key"], b["doc_count"]) for b in buckets]


def _to_entry(hit: dict[str, Any]) -> LogEntry:
    source = hit.get("_source", {}) or {}
    source.setdefault("id", hit.get("_id"))
    # ES 的 _source 里可能有我们不认识的字段，LogEntry 配了 extra="ignore" 会丢掉
    return LogEntry.model_validate(source)


# 供健康检查用：把「依赖不可用」这个语义统一出去
__all__ = [
    "DependencyUnavailable",
    "close_client",
    "count_by_level",
    "get_client",
    "get_log_by_id",
    "ping",
    "search_logs",
    "top_templates",
]