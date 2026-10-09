"""match_exception_rules —— 单独调规则引擎。

★ 为什么规则在阶段 2 已经跑过了，还要给 Agent 一个工具？
  因为两者看的文本范围不同：
    · 阶段 2 只看**触发日志**（快，要秒级出结论）
    · 这个工具可以传入**Agent 自己发现的新线索**（比如它从别的日志里
      找到了一段没见过的堆栈）
  所以它是"用规则引擎复核新证据"，不是重复劳动。
"""

from __future__ import annotations

from langchain_core.tools import tool

from trace_mind_ai.domain.rules.base import RuleContext
from trace_mind_ai.domain.rules.registry import get_registry
from trace_mind_ai.schemas.log import LogEntry
from trace_mind_ai.tools.context import current


@tool
async def match_exception_rules(snippet: str) -> str:
    """把一段文本交给确定性规则引擎匹配，拿到经验处置方案。

    什么时候用：
      · 你从日志里发现了新的异常片段，想确认有没有对应的已知处置方案
      · 想验证自己的判断 —— 规则命中说明这是**已知问题**，处置方案是确定的

    什么时候不要用：
      · 只是想看触发日志的规则匹配结果（阶段 2 已经做过了）

    Args:
        snippet: 要匹配的文本，通常是异常堆栈或错误日志片段。
    """
    ctx = current()
    ctx.consume()
    ctx.used_tools.append("match_exception_rules")

    # 用临时的 LogEntry 承载这段文本，复用同一套规则引擎
    probe = LogEntry(
        projectId=ctx.project_id,
        level=ctx.trigger_log.level,
        rawText=snippet[:4000],
    )
    rule_ctx = RuleContext(
        project_id=ctx.project_id,
        trigger_log=probe,
        templates=[],
        neighbors=[],
    )

    match = get_registry().match(rule_ctx)
    if match is None:
        return (
            "规则引擎没有命中已知模式。说明这不是常见的标准故障，"
            "需要依靠日志事实和推理来定位。\n"
            "（这条信息本身有用：它意味着**没有现成的处置方案可以套**。）"
        )

    lines = [
        f"规则命中：{match.analyzer}（置信度 {match.confidence}）",
        "",
        "经验处置方案：",
        match.solution,
    ]
    if match.evidence:
        lines.extend(["", "命中依据："])
        lines.extend(f"  · {item}" for item in match.evidence)

    return "\n".join(lines)