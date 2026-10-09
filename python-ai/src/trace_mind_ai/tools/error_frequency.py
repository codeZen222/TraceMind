"""count_error_frequency —— 错误频次统计。

★ 这个工具的存在意义：**它能用几百个 token 回答"问题有多大"**。
  同样的信息如果靠 search_logs 拉正文，要烧几千个 token。
  给 Agent 提供"便宜的聚合视图"，是控制成本最直接的手段。
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from langchain_core.tools import tool

from trace_mind_ai.infra import es
from trace_mind_ai.tools.context import current


def _parse_ts(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


@tool
async def count_error_frequency(window_minutes: int = 30) -> str:
    """统计当前项目在时间窗内的错误分布：各级别数量 + 出现最多的日志模板。

    什么时候用：
      · 判断问题的影响面（是零星几条还是成百上千条）
      · 看是不是同一个模板在反复刷屏（那就是同一个根因）
      · 在决定要不要深入检索之前，先用它探一下规模

    什么时候不要用：
      · 需要看日志的**具体内容** → 用 search_logs

    Args:
        window_minutes: 以触发日志时间为基准向前统计多少分钟。默认 30。
    """
    ctx = current()
    ctx.consume()
    ctx.used_tools.append("count_error_frequency")

    trigger_ts = _parse_ts(ctx.trigger_log.timestamp)
    start_ts = None
    if trigger_ts is not None:
        start_ts = (trigger_ts - timedelta(minutes=window_minutes)).isoformat()

    by_level = await es.count_by_level(
        project_id=ctx.project_id, start_ts=start_ts, end_ts=None
    )
    tops = await es.top_templates(project_id=ctx.project_id, size=5)

    if not by_level and not tops:
        return (
            f"项目 {ctx.project_id} 在最近 {window_minutes} 分钟内没有统计数据。"
            "可能是：该项目还没有日志入库，或检索服务不可用。"
        )

    parts = [f"最近 {window_minutes} 分钟，项目 {ctx.project_id}：", ""]

    if by_level:
        total = sum(by_level.values())
        parts.append(f"总计 {total} 条")
        # 按数量倒序，ERROR 排前面
        for lvl, cnt in sorted(by_level.items(), key=lambda x: -x[1]):
            parts.append(f"  {lvl:<6} {cnt}")
    else:
        parts.append("（级别分布数据不可用）")

    if tops:
        parts.append("")
        parts.append("出现最多的日志模板：")
        for idx, (template, cnt) in enumerate(tops, 1):
            short = template if len(template) <= 120 else template[:120] + "…"
            parts.append(f"  {idx}. ×{cnt}  {short}")

    parts.append("")
    parts.append(
        "判读提示：如果某个模板的数量远超其他（比如占了一半以上），"
        "基本可以认定是同一个根因在重复触发，值得直接深挖它。"
    )
    return "\n".join(parts)