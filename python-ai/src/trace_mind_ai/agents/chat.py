"""追问链 —— 两条路：Agent（带工具）与纯 token 流（降级）。

★ 为什么追问也要 Agent：
  追问里有一类是"你确定吗？把那条日志原文给我看看" ——
  纯问答模型这时只能凭上下文编，**它得能真的去查**。
  而"再去查一次"的能力诊断那套已经全有了：同一批工具、同一套预算中间件、同一个 ToolContext。

★ 为什么降级路径（stream_chat）不删：
  Agent 涉及多次模型调用 + 工具，出错面比单轮大得多。
  留一条"退回单轮问答"的路，最坏的结果只是"这次没帮你查证"，
  而不是"追问功能整个不能用"。**这是这个文件里最重要的一条设计。**

★ 一条必须守住的细节：
  产出的文本 token **绝不能 strip**（英文单词之间那个前导空格靠它传过去）。
  现在帧是 JSON，空格被包在 JSON 字符串内部，天然安全 ——
  前端只要别对 payload 整个做 trim 就行。
"""

from __future__ import annotations

import json
import time
from collections.abc import AsyncIterator
from typing import Any

from langchain.agents import create_agent
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from trace_mind_ai.agents.middleware import TokenUsageMiddleware, ToolBudgetMiddleware
from trace_mind_ai.agents.prompts import CHAT_AGENT_SYSTEM_PROMPT, CHAT_SYSTEM_PROMPT
from trace_mind_ai.core.llm import ModelProfile, get_chat_model
from trace_mind_ai.core.logging import get_logger
from trace_mind_ai.schemas.chat import ChatMessage, ChatRequest
from trace_mind_ai.schemas.log import LogEntry
from trace_mind_ai.tools.context import ToolContext, tool_context
from trace_mind_ai.tools.registry import ALL_TOOLS

log = get_logger(__name__)

#: 历史消息最多带多少条。追问是多轮的，不带历史会"失忆"，
#: 全带又会爆上下文 —— 10 条（5 轮）是个平衡点。
_MAX_HISTORY = 10

#: 追问的工具预算。**比诊断紧得多**（诊断是 6 次 / 90 秒）：
#: 追问是交互式的，让用户等 3 次工具调用已经是忍耐极限了。
_MAX_TOOL_CALLS = 3
_MAX_AGENT_SECONDS = 25.0

#: LangGraph 的循环上限。独立于 middleware 的第二道闸门。
_RECURSION_LIMIT = 8

#: 工具结果写进帧时的截断长度。前端只展示摘要，完整内容在工具自己的返回值里。
_SUMMARY_LIMIT = 400


# ══════════════════════════════════════════════════════════
# 帧（对前端的契约）
# ══════════════════════════════════════════════════════════

def text_frame(content: str) -> dict[str, Any]:
    """一段回答正文。前端拼成气泡内容，Java 也只把这个存库。"""
    return {"type": "text", "content": content}


def tool_call_frame(tool: str, args: dict[str, Any]) -> dict[str, Any]:
    """Agent 要调工具了 —— 前端据此显示"正在查日志…"。"""
    return {"type": "tool_call", "tool": tool, "args": _shrink(args)}


def tool_result_frame(tool: str, summary: str, truncated: bool, cost_ms: int) -> dict[str, Any]:
    return {
        "type": "tool_result",
        "tool": tool,
        "summary": summary,
        "truncated": truncated,
        "costMs": cost_ms,
    }


# ══════════════════════════════════════════════════════════
# 消息组装
# ══════════════════════════════════════════════════════════

def _context_messages(
    question: str,
    history: list[ChatMessage],
    diagnosis_context: str,
) -> list[HumanMessage | AIMessage]:
    """诊断上下文 + 历史 + 本次问题。

    ★ 把诊断上下文作为**第一条用户消息**塞进去，而不是拼进 system prompt：
      system prompt 应该稳定（利于 prompt 缓存），
      而诊断上下文每次请求都不同。
    """
    messages: list[HumanMessage | AIMessage] = [
        HumanMessage(content=f"这是本次诊断的完整上下文：\n\n{diagnosis_context}"),
        AIMessage(content="明白了，我已经了解这次诊断的全貌。请提问。"),
    ]
    for item in history[-_MAX_HISTORY:]:
        if item.role == "user":
            messages.append(HumanMessage(content=item.content))
        else:
            messages.append(AIMessage(content=item.content))
    messages.append(HumanMessage(content=question))
    return messages


def _build_messages(
    question: str,
    history: list[ChatMessage],
    diagnosis_context: str,
) -> list[SystemMessage | HumanMessage | AIMessage]:
    """降级路径用：自带 system prompt 的完整消息列表。"""
    return [
        SystemMessage(content=CHAT_SYSTEM_PROMPT),
        *_context_messages(question, history, diagnosis_context),
    ]


# ══════════════════════════════════════════════════════════
# 路径一：Agent（有工具）
# ══════════════════════════════════════════════════════════

async def stream_chat_agent(
    request: ChatRequest,
    *,
    session: dict[str, Any],
    diagnosis_context: str,
    trigger_log: LogEntry | None,
) -> AsyncIterator[dict[str, Any]]:
    """跑追问 Agent，产出**帧字典**（不是裸 token），由调用方 encode 成 SSE。

    ★ 注意这里没有把 system prompt 拼进 messages：
      create_agent(system_prompt=...) 会自己加，
      再塞一条 SystemMessage 进去会出现两个 system prompt 打架。
    """
    budget = ToolBudgetMiddleware(max_calls=_MAX_TOOL_CALLS, max_seconds=_MAX_AGENT_SECONDS)
    tokens = TokenUsageMiddleware()
    agent = create_agent(
        model=get_chat_model(ModelProfile.CHAT),
        tools=ALL_TOOLS,
        system_prompt=CHAT_AGENT_SYSTEM_PROMPT,
        middleware=[budget, tokens],
    )

    project_id = str(session.get("projectId") or "")
    ctx = ToolContext(
        project_id=project_id,
        session_id=str(session.get("sessionId") or request.sessionId),
        # 触发日志可能查不到（ES 里没有 / 已被清理）——
        # 给个只有 id + projectId 的占位，别让工具因为缺上下文而整条链路失败。
        # 工具真正依赖的是 project_id，trigger_log 只用来定位时间窗。
        trigger_log=trigger_log
        or LogEntry(id=str(session.get("triggerLogId") or "") or None, projectId=project_id or None),
    )

    tool_started: dict[str, float] = {}
    produced_text = False

    # ★ with 包住整段：保证异常路径也能清掉 contextvar，
    #   否则同一个 worker 处理下一个请求时会拿到上一个的 project_id（串项目）
    with tool_context(ctx):
        async for raw in agent.astream_events(
            {"messages": _context_messages(request.question, request.history, diagnosis_context)},
            version="v2",
            config={"recursion_limit": _RECURSION_LIMIT},
        ):
            kind = raw.get("event")

            if kind == "on_tool_start":
                name = raw.get("name") or "unknown"
                tool_started[raw.get("run_id", name)] = time.perf_counter()
                args = raw.get("data", {}).get("input") or {}
                if not isinstance(args, dict):
                    args = {"input": str(args)}
                yield tool_call_frame(name, args)

            elif kind == "on_tool_end":
                name = raw.get("name") or "unknown"
                started = tool_started.pop(raw.get("run_id", name), None)
                cost_ms = int((time.perf_counter() - started) * 1000) if started else 0
                summary, truncated = _summarize(_stringify(raw.get("data", {}).get("output")))
                yield tool_result_frame(name, summary, truncated, cost_ms)

            elif kind == "on_chat_model_stream":
                text = _chunk_text(raw.get("data", {}).get("chunk"))
                if text:
                    produced_text = True
                    # ★ 不 trim —— 前导空格是英文单词的分隔符
                    yield text_frame(text)

            elif kind == "on_chat_model_end":
                usage = _extract_usage(raw.get("data", {}).get("output"))
                if usage:
                    tokens.record(usage)

    log.info(
        "chat.agent_finished",
        tool_calls=len(ctx.used_tools),
        tools=ctx.used_tools,
        tokens=tokens.total,
    )

    # 兜底：只调了工具没吐文本（预算被打断 / 到递归上限）时，
    # 不能让用户对着一个空气泡。如实说一句，比留空白好。
    if not produced_text:
        yield text_frame("（这次没能给出回答：可能工具查询耗尽了本轮预算，换个问法或稍后再试。）")


# ══════════════════════════════════════════════════════════
# 路径二：纯 token 流（降级，无工具）
# ══════════════════════════════════════════════════════════

async def stream_chat(
    request: ChatRequest,
    *,
    diagnosis_context: str,
) -> AsyncIterator[str]:
    """产出**裸 token**（不是帧）。调用方包装成帧 + SSE 并补 [DONE]。

    Agent 抛异常 / 超预算时走这条：模型还是能解释结论，只是不能再去查证。
    """
    model = get_chat_model(ModelProfile.CHAT)
    messages = _build_messages(request.question, request.history, diagnosis_context)

    try:
        async for chunk in model.astream(messages):
            text = _chunk_text(chunk)
            if text:
                yield text
    except Exception as exc:
        # 异常也要给出人类可读的收尾，否则前端只会看到一个空白的助手消息
        log.error("chat.stream_failed", error=type(exc).__name__, detail=str(exc)[:200])
        yield f"\n\n（回答中断：{type(exc).__name__}）"


# ══════════════════════════════════════════════════════════
# 辅助
# ══════════════════════════════════════════════════════════
# 说明：下面三个函数与 agents/diagnosis.py 里的同名实现语义一致。
# 之所以各写一份而不是互相 import —— 那边是下划线开头的模块私有函数，
# 跨模块引用私有名比重复这几行更糟。

def _chunk_text(chunk: object) -> str:
    """抠出文本。两种形状都要兼容（str / list[dict]），否则打字机效果会全空。"""
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
    """从模型输出里取 token 用量。取不到就算了，不报错（统计只是可观测性）。"""
    if output is None:
        return None
    usage = getattr(output, "usage_metadata", None)
    if usage is None and isinstance(output, dict):
        usage = output.get("usage_metadata") or output.get("usage")
    if isinstance(usage, dict):
        return {k: int(v) for k, v in usage.items() if isinstance(v, (int, float))}
    return None


def _stringify(output: Any) -> str:
    """把工具返回值转成字符串 —— 可能是 ToolMessage / str / dict 等各种形状。"""
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
    """截断工具返回。第二个返回值是"是否截断" —— 这个标记必须如实上报。"""
    flat = " ".join(text.split())
    if len(flat) <= _SUMMARY_LIMIT:
        return flat, False
    return flat[:_SUMMARY_LIMIT] + "…", True


def _shrink(args: dict[str, Any]) -> dict[str, Any]:
    """工具入参不能太大 —— 它要推给前端、前端还要展示。"""
    shrunk: dict[str, Any] = {}
    for key, value in args.items():
        text = str(value)
        shrunk[key] = text[:200] + "…" if len(text) > 200 else value
    return shrunk
