"""check_project_health —— 项目整体健康度。

★ 这个工具提供的是**纵向视角**：前面几个工具都在看"这一条日志的相关信息"，
  它看的是"这个项目现在整体是什么状态"（采集任务在不在跑、降级没有、
  累计错误多少）。很多"某条日志报错"的真正原因是"采集任务早停了"。
"""

from __future__ import annotations

from langchain_core.tools import tool

from trace_mind_ai.infra import es, mysql
from trace_mind_ai.tools.context import current


@tool
async def check_project_health() -> str:
    """查看当前项目的整体状态：配置、采集任务是否在运行、是否处于降级、日志总量。

    什么时候用：
      · 在深入分析单条日志之前，先确认这个项目的采集链路是正常的
      · 怀疑"日志不全 / 数据缺失"是采集端的问题而不是业务问题
      · 线上故障排查时，快速建立对系统状态的整体认知

    什么时候不要用：
      · 已经明确问题在单条日志里，直接分析它更快

    不需要参数 —— 项目由当前会话上下文决定。
    """
    ctx = current()
    ctx.consume()
    ctx.used_tools.append("check_project_health")

    project = await mysql.get_project(ctx.project_id)
    tasks = await mysql.get_monitor_tasks(ctx.project_id)
    by_level = await es.count_by_level(
        project_id=ctx.project_id, start_ts=None, end_ts=None
    )
    es_ok, es_detail = await es.ping()

    lines = [f"项目 {ctx.project_id} 健康检查：", ""]

    # ── 项目配置 ──
    if project:
        lines.append("【项目配置】")
        for label, key in (("名称", "name"), ("来源", "source"),
                           ("语言", "language"), ("状态", "status"),
                           ("日志路径", "logPath")):
            lines.append(f"  {label}: {project.get(key) or '（未设置）'}")
    else:
        lines.append("【项目配置】查询失败或项目不存在")

    # ── 采集任务 ──
    lines.extend(["", "【采集任务】"])
    if not tasks:
        lines.append("  没有采集任务（该项目未开启监听）")
    else:
        for task in tasks:
            degraded = "⚠️ 降级中" if task.get("degraded") else "正常"
            lines.append(
                f"  任务 {task.get('taskId')}: 状态={task.get('status')} "
                f"({degraded}) 错误数={task.get('errorCount', 0)}"
            )
            if task.get("degraded"):
                lines.append(
                    "    ↑ 降级说明推理服务调用连续失败。"
                    "**此时模板提取和 LLM 分析可能都不可用，"
                    "日志事实仍然可信，但推断类结论要打折看。**"
                )

    # ── 日志规模 ──
    lines.extend(["", "【日志规模】"])
    if by_level and es_ok:
        total = sum(by_level.values())
        lines.append(f"  总计 {total} 条")
        for lvl, cnt in sorted(by_level.items(), key=lambda x: -x[1]):
            lines.append(f"    {lvl:<6} {cnt}")
    elif not es_ok:
        lines.append(f"  ⚠️ 检索服务不可用（{es_detail}），无法统计")
    else:
        lines.append("  没有日志数据")

    return "\n".join(lines)