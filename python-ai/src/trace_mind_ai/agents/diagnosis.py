"""诊断 Agent —— LangChain 1.x 的 create_agent。

★ 关于 LangGraph：
  `create_agent` 返回的**本身就是一个编译好的 LangGraph StateGraph**。
  所以我们"在用 LangGraph"，只是没手写图。
  什么时候才需要下沉到手写图？三个信号：
    · 需要分支流程（不同异常走不同分析路径）
    · 需要并行调工具再汇总
    · 需要断点续跑 / 人工介入
  **我们这三个需求都不强** —— 标准「模型 ⇄ 工具」循环，create_agent 已经包好了，
  用 middleware 就能插进限流 / 校验 / 裁剪。**手写图是给自己找复杂度。**
"""

from __future__ import annotations

import json
import re
import time
from collections.abc import AsyncIterator
from typing import Any

from langchain.agents import create_agent

from trace_mind_ai.agents.middleware import TokenUsageMiddleware, ToolBudgetMiddleware
from trace_mind_ai.agents.prompts import DIAGNOSIS_SYSTEM_PROMPT
from trace_mind_ai.core.config import get_settings
from trace_mind_ai.core.llm import ModelProfile, get_chat_model
from trace_mind_ai.core.logging import get_logger
from trace_mind_ai.schemas.event import DiagnosisEvent
from trace_mind_ai.schemas.log import LogEntry
from trace_mind_ai.tools.context import ToolContext, tool_context
from trace_mind_ai.tools.registry import ALL_TOOLS

log = get_logger(__name__)

#: 工具返回摘要写进事件时的截断长度。
#: 前端 ToolCallCard 展示不了太长；而且事件要落库，太长会让表迅速膨胀。
_SUMMARY_LIMIT = 400

#: LangGraph 的循环上限。这是**独立于 middleware 的第二道闸门** ——
#: 即使 middleware 因版本变化失效，这一条依然生效。
_RECURSION_LIMIT = 25

_JSON_BLOCK = re.compile(r"```json\s*(\{.*?\})\s*```", re.DOTALL)


def _build_agent(budget: ToolBudgetMiddleware, tokens: TokenUsageMiddleware) -> Any:
    return create_agent(
        model=get_chat_model(ModelProfile.DIAGNOSIS),
        tools=ALL_TOOLS,
        system_prompt=DIAGNOSIS_SYSTEM_PROMPT,
        middleware=[budget, tokens],
    )

# 把已有信息拼成首轮输入。
def _render_input(
    project_id: str, trigger_log: LogEntry, templates: list[str], neighbors: list[LogEntry]
) -> str:
    """把已有信息拼成首轮输入。

    ★ 为什么要把邻居日志提前塞进来，而不是让 Agent 自己去查：
      触发日志单独看往往信息不足（比如只有一行 "NPE"），
      但把它前后的日志一起看，根因经常一眼可见。
      **提前给足上下文，比让模型多调一次工具更省 token。**
    """
    parts = [
        f"# 诊断请求",
        f"项目 ID: {project_id}",
        "",
        "## 触发日志",
        f"- 时间: {trigger_log.timestamp or '未知'}",
        f"- 级别: {trigger_log.level or '未知'}",
        f"- 文件: {trigger_log.filePath or '未知'}:{trigger_log.lineNumber or '?'}",
        f"- 内容: {trigger_log.rawText or '(空)'}",
    ]
    if trigger_log.stackTrace:
        stack = trigger_log.stackTrace
        if len(stack) > 3000:
            stack = stack[:3000] + "\n…（堆栈已截断）"
        parts.extend(["", "## 堆栈", "```", stack, "```"])

    if templates:
        parts.extend(["", "## 已提取的日志模板", *[f"- {t}" for t in templates[:5]]])

    if neighbors:
        parts.extend(["", "## 同时段相邻日志（最近 10 条）"])
        for item in neighbors[:10]:
            text = (item.rawText or "")[:200]
            parts.append(f"- [{item.timestamp or '?'}] [{item.level or '?'}] {text}")

    parts.extend(["", "请开始诊断。"])
    return "\n".join(parts)


async def run_diagnosis_agent(
    *,
    project_id: str,
    session_id: str,
    trigger_log: LogEntry,
    templates: list[str],
    neighbors: list[LogEntry],
) -> AsyncIterator[DiagnosisEvent]:
    """跑 Agent，产出事件流。

    这个函数是 pipeline 注入给阶段 3 的 agent_runner。
    """
    settings = get_settings()
    budget = ToolBudgetMiddleware(
        max_calls=6,
        max_seconds=min(90.0, settings.budget.max_duration_s * 6),
    )
    tokens = TokenUsageMiddleware()
    agent = _build_agent(budget, tokens)

    ctx = ToolContext(
        project_id=project_id,
        session_id=session_id,
        trigger_log=trigger_log,
    )

    tool_started: dict[str, float] = {}
    final_text_parts: list[str] = []

    # ★ tool_context 用 with 包住整段：保证异常路径也能清理掉 contextvar，
    #   否则同一个 worker 处理下一个请求时会拿到上一个的 project_id（串项目！）
    with tool_context(ctx):
        async for raw in agent.astream_events(
            {"messages": [{"role": "user", "content": _render_input(
                project_id, trigger_log, templates, neighbors
            )}]},
            version="v2",
            config={"recursion_limit": _RECURSION_LIMIT},
        ):
            kind = raw.get("event")

            # ── 工具开始 ──
            if kind == "on_tool_start":
                name = raw.get("name") or "unknown"
                tool_started[raw.get("run_id", name)] = time.perf_counter()
                args = raw.get("data", {}).get("input") or {}
                if not isinstance(args, dict):
                    args = {"input": str(args)}
                yield DiagnosisEvent.tool_call(name, _shrink_args(args))

            # ── 工具结束 ──
            elif kind == "on_tool_end":
                name = raw.get("name") or "unknown"
                started = tool_started.pop(raw.get("run_id", name), None)
                cost_ms = int((time.perf_counter() - started) * 1000) if started else 0
                output = _stringify(raw.get("data", {}).get("output"))
                summary, truncated = _summarize(output)
                yield DiagnosisEvent.tool_result(name, summary, truncated, cost_ms)

            # ── 模型流式输出 ──
            elif kind == "on_chat_model_stream":
                chunk = raw.get("data", {}).get("chunk")
                text = _chunk_text(chunk)
                if text:
                    final_text_parts.append(text)
                    # ★ 不 trim —— 前导空格是英文单词的分隔符
                    yield DiagnosisEvent.token(text)

            # ── token 用量 ──
            elif kind == "on_chat_model_end":
                usage = _extract_usage(raw.get("data", {}).get("output"))
                if usage:
                    tokens.record(usage)

    log.info(
        "agent.finished",
        tool_calls=len(ctx.used_tools),
        tools=ctx.used_tools,
        tokens=tokens.total,
    )

    # ── 解析最终结论 ──
    full_text = "".join(final_text_parts)
    parsed = _parse_result(full_text)
    if parsed is None:
        yield DiagnosisEvent.error(
            3,
            "Agent 未能给出结构化的诊断结论（可能被预算中断或输出格式异常）",
        )
        return

    yield DiagnosisEvent.result(
        root_cause=parsed["rootCause"],
        evidence=parsed["evidence"],
        suggestion=parsed["suggestion"],
        confidence=parsed["confidence"],
    )


# ══════════════════════════════════════════════════════════
# 辅助
# ══════════════════════════════════════════════════════════


def _shrink_args(args: dict[str, Any]) -> dict[str, Any]:
    """工具入参事件不能太大 —— 它要落库、要推 WS、前端还要展示。"""
    shrunk: dict[str, Any] = {}
    for key, value in args.items():
        text = str(value)
        shrunk[key] = text[:200] + "…" if len(text) > 200 else value
    return shrunk


def _stringify(output: Any) -> str:
    """把工具返回值转成字符串。

    工具的返回可能是 ToolMessage、str、dict 等各种形状，
    这里统一处理 —— 不要假设是 str。
    """
    if output is None:
        return ""
    if isinstance(output, str):
        return output
    content = getattr(output, "content", None)
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return " ".join(str(part) for part in content)
    try:
        return json.dumps(output, ensure_ascii=False, default=str)
    except Exception:
        return str(output)


def _summarize(text: str) -> tuple[str, bool]:
    """截断工具返回。第二个返回值是「是否截断」—— 这个标记必须如实上报。"""
    flat = " ".join(text.split())
    if len(flat) <= _SUMMARY_LIMIT:
        return flat, False
    return flat[:_SUMMARY_LIMIT] + "…", True


def _chunk_text(chunk: Any) -> str:
    """从流式 chunk 里抠出文本。

    ⚠️ 不同版本 / 不同模型的 chunk.content 形状不同：
        · str          —— 大多数情况
        · list[dict]   —— 有些模型会返回结构化块（比如带 type/content 的列表）
      两种都要处理，否则 token 事件会全空，前端的打字机效果就没了。
    """
    if chunk is None:
        return ""
    content = getattr(chunk, "content", None)
    if content is None and isinstance(chunk, dict):
        content = chunk.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(
            part.get("text", "") if isinstance(part, dict) else str(part)
            for part in content
        )
    return ""


def _extract_usage(output: Any) -> dict[str, int] | None:
    """从模型输出里取 token 用量。取不到就算了，不报错。"""
    if output is None:
        return None
    usage = getattr(output, "usage_metadata", None)
    if usage is None and isinstance(output, dict):
        usage = output.get("usage_metadata") or output.get("usage")
    if isinstance(usage, dict):
        return {k: int(v) for k, v in usage.items() if isinstance(v, (int, float))}
    return None


def _parse_result(text: str) -> dict[str, Any] | None:
    """从模型输出里解析最终结论。

    策略：优先找 ```json 代码块；找不到就尝试把整个文本当 JSON 解析
    （模型有时会直接输出裸 JSON）。
    """
    candidates: list[str] = []
    match = _JSON_BLOCK.search(text)
    if match:
        candidates.append(match.group(1))

    start = text.rfind("{")
    end = text.rfind("}")
    if start != -1 and end > start:
        candidates.append(text[start : end + 1])

    for candidate in candidates:
        try:
            data = json.loads(candidate)
        except json.JSONDecodeError:
            continue
        if not isinstance(data, dict) or "rootCause" not in data:
            continue

        evidence = data.get("evidence") or []
        if isinstance(evidence, str):
            evidence = [evidence]

        return {
            "rootCause": str(data.get("rootCause") or "")[:1000],
            "evidence": [str(item)[:500] for item in evidence][:8],
            "suggestion": str(data.get("suggestion") or "")[:4000],
            "confidence": _clamp(data.get("confidence")),
        }
    return None


def _clamp(value: Any) -> float:
    """置信度钳到 [0,1]。模型偶尔会给出 1.2 或 "0.8" 这种值。"""
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0.6      # 取不到就给个中等值，别给 0.9（那是虚假高置信）
    return max(0.0, min(1.0, number))