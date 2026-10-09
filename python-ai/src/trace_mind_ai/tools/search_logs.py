"""search_logs —— 按关键词检索日志。"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from langchain_core.tools import tool

from trace_mind_ai.core.logging import get_logger
from trace_mind_ai.infra import es
from trace_mind_ai.tools.context import current

log = get_logger(__name__)

_MAX_SUMMARY_CHARS = 2000


def _parse_ts(value: str | None) -> datetime | None:
    """解析 ISO-8601。Java 侧给的是 2026-09-10T18:29:03.512Z 这种形式。"""
    if not value:
        return None
    try:
        # Python 3.11+ 的 fromisoformat 能直接吃结尾的 Z
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


@tool
async def search_logs(
    keyword: str,
    window_minutes: int = 5,
    level: str = "ALL",
    limit: int = 20,
) -> str:
    """按关键词在当前项目的日志里检索。

    什么时候用：
      · 想确认某类异常在触发时间点前后出现了多少次、都有哪些
      · 想看异常发生时上下文里还有什么别的日志

    什么时候不要用：
      · 只是想知道数量分布 → 用 count_error_frequency（更快，不拉正文）
      · 想找历史上类似问题是怎么解决的 → 用 search_similar_cases
      · 想看这个项目最近反复在报什么 → 直接看 check_project_health

    Args:
        keyword: 检索关键词。中文、异常类名、错误码都可以。
        window_minutes: 以触发日志的时间为基准，向前后各取多少分钟。默认 5。
        level: 日志级别过滤，ERROR / WARN / INFO / DEBUG，或 ALL 表示不过滤。
        limit: 最多返回多少条。默认 20，上限 100。
    """
    ctx = current()
    ctx.consume()
    ctx.used_tools.append("search_logs")

    limit = max(1, min(limit, 100))
    trigger_ts = _parse_ts(ctx.trigger_log.timestamp)
    start_ts = end_ts = None
    if trigger_ts is not None:
        start_ts = (trigger_ts - timedelta(minutes=window_minutes)).isoformat()
        end_ts = (trigger_ts + timedelta(minutes=window_minutes)).isoformat()

    entries = await es.search_logs(
        project_id=ctx.project_id,
        keyword=keyword,
        level=level,
        start_ts=start_ts,
        end_ts=end_ts,
        size=limit,
    )

    if not entries:
        # ★ 返回"没找到"而不是空字符串 —— 模型需要知道这是"确定了没有"，
        #   而不是"工具坏了"。措辞上要明确。
        return (
            f"在项目 {ctx.project_id} 中，以 {window_minutes} 分钟为窗口检索 "
            f"「{keyword}」，没有找到匹配的日志。\n"
            f"建议：换更宽泛的关键词，或把 window_minutes 调大。"
        )

    lines = [f'关键词「{keyword}」命中 {len(entries)} 条（时间窗 ±{window_minutes} 分钟）：', ""]
    used = 0
    truncated = False
    for entry in entries:
        text = (entry.rawText or "").strip().replace("\n", " ")
        record = f"[{entry.timestamp or '?'}] [{entry.level or '?'}] {text}"
        if len(record) > 300:
            record = record[:300] + "…"
            truncated = True
        if used + len(record) > _MAX_SUMMARY_CHARS:
            truncated = True
            break
        lines.append(record)
        used += len(record)

    if truncated:
        lines.append("")
        lines.append("（内容已截断。需要更多细节请缩小检索范围或减少 limit。）")

    return "\n".join(lines)