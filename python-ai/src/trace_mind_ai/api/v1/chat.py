"""POST /v1/chat —— 追问，Agent 帧流（带工具），失败降级成纯 token 流。

★★★ 三个必须守住的细节 ★★★

① Java 侧只发了 {sessionId, question, history}，**没有 projectId**。
   所以这里必须先拿 sessionId 反查 agent_session 表，
   才能知道这次追问是针对哪个项目、哪次诊断。
   （Agent 的工具全靠 project_id 干活，错一个就是跨项目读数据。）

② **帧的 content 绝不能 strip**。英文单词之间的前导空格靠它传过去。
   现在帧是 JSON，空格包在字符串内部，JSON.parse 出来原样保留。

③ 帧形状变了，Java 侧 `DiagnosisServiceImpl.streamAnswer()` 必须同步：
   它现在会把每一帧拼进 chat_message 落库，只认 `type == "text"` 的 content。
   不改的话，历史回放里会看到一屏 JSON。
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Annotated, Any

from fastapi import APIRouter, Body
from fastapi.responses import StreamingResponse

from trace_mind_ai.agents.chat import stream_chat, stream_chat_agent, text_frame
from trace_mind_ai.api.deps import TokenDep
from trace_mind_ai.core.exceptions import TraceMindAIError
from trace_mind_ai.core.logging import bind_trace, get_logger
from trace_mind_ai.core.sse import DONE_FRAME, SSE_HEADERS, encode
from trace_mind_ai.infra import es, mysql
from trace_mind_ai.schemas.chat import ChatRequest
from trace_mind_ai.schemas.log import LogEntry

router = APIRouter(tags=["chat"])
log = get_logger(__name__)

#: 上下文里最多带几条"诊断阶段已经查过的工具"
_MAX_TOOL_HISTORY = 4


class SessionNotFoundError(TraceMindAIError):
    code = "SESSION_NOT_FOUND"
    http_status = 404


@router.post("/chat")
async def chat(
    payload: Annotated[ChatRequest, Body()],
    _token: TokenDep,
) -> StreamingResponse:
    # ★ 会话存在性检查放在返回 StreamingResponse **之前** ——
    #   这时候还能返回真实的 404。
    #   一旦开始推流，HTTP 状态码已经发出去了，改不了。
    session = await mysql.get_session(payload.sessionId)
    if session is None:
        raise SessionNotFoundError(f"诊断会话不存在: {payload.sessionId}")

    project_id = str(session.get("projectId") or "")
    # 触发日志只查一次：上下文要用它，Agent 的 ToolContext 也要用它
    trigger_log = await _load_trigger_log(project_id, str(session.get("triggerLogId") or ""))
    context = await _build_context(payload.sessionId, session, trigger_log)

    return StreamingResponse(
        _frame_stream(payload, context, session=session, trigger_log=trigger_log),
        media_type="text/event-stream",
        headers=SSE_HEADERS,
    )


async def _frame_stream(
    payload: ChatRequest,
    context: str,
    *,
    session: dict[str, Any],
    trigger_log: LogEntry | None,
) -> AsyncIterator[str]:
    """把 Agent 的帧字典 encode 成 SSE。

    降级策略：Agent 出任何问题（预算超限、工具报错、模型不支持结构化输出……）
    都退回 `stream_chat` 单轮问答 —— 用户看到的是"这次没帮你查证"，
    而不是一个报错。
    """
    bind_trace(payload.sessionId, endpoint="chat")
    log.info("chat.start", question_len=len(payload.question), history=len(payload.history))

    try:
        async for frame in stream_chat_agent(
            payload,
            session=session,
            diagnosis_context=context,
            trigger_log=trigger_log,
        ):
            yield encode(frame)
    except asyncio.CancelledError:
        # ★ 用户点"停止"走的是这条路。**必须原样抛出** ——
        #   当成普通异常处理的话，用户点了停止却收到一句"回答生成失败"。
        #   而且吞掉取消会让 asyncio 的任务清理逻辑失效。
        log.info("chat.cancelled")
        raise
    except Exception as exc:
        log.warning("chat.agent_fallback", error=type(exc).__name__, detail=str(exc)[:200])
        try:
            async for token in stream_chat(payload, diagnosis_context=context):
                yield encode(text_frame(token))
        except asyncio.CancelledError:
            raise
        except Exception as exc2:
            log.exception("chat.failed", error=type(exc2).__name__)
            yield encode(text_frame(f"（回答生成失败：{type(exc2).__name__}）"))
    finally:
        yield DONE_FRAME
        log.info("chat.stream_closed")


async def _build_context(
    session_id: str,
    session: dict[str, Any],
    trigger_log: LogEntry | None,
) -> str:
    """组装诊断上下文给模型。

    包含：项目信息 + 会话结论 + 触发日志 + 诊断阶段已经查过的工具。
    """
    project_id = str(session.get("projectId") or "")
    parts = [
        "# 诊断会话",
        f"- 会话 ID: {session_id}",
        f"- 项目 ID: {project_id}",
        f"- 状态: {session.get('status')}",
    ]
    if session.get("rootCause"):
        parts.append(f"- 已得出的根因: {session['rootCause']}")
    if session.get("suggestion"):
        parts.append(f"- 已给出的建议: {session['suggestion']}")

    if trigger_log is not None:
        parts.extend([
            "",
            "## 触发日志",
            f"- 时间: {trigger_log.timestamp or '未知'}",
            f"- 级别: {trigger_log.level or '未知'}",
            f"- 位置: {trigger_log.filePath or '未知'}:{trigger_log.lineNumber or '?'}",
            f"- 内容: {trigger_log.rawText or '(空)'}",
        ])
        if trigger_log.stackTrace:
            parts.extend(["", "## 堆栈", "```", trigger_log.stackTrace[:2000], "```"])

    # ★ 诊断阶段已经跑过的工具，追问时不必重查一遍 ——
    #   既省钱又快，而且模型答"你凭什么这么说"时手上就有证据。
    tool_history = await _recent_tool_calls(session_id)
    if tool_history:
        parts.extend(["", "## 诊断阶段已经查过（不必重复查）", *tool_history])

    return "\n".join(parts)


async def _recent_tool_calls(session_id: str) -> list[str]:
    """诊断阶段的工具调用摘要。

    ★ 查不到就当没有这段上下文，**绝不让它影响追问** ——
      追问的核心价值是回答问题，少一段背景只是答得浅一点。
    """
    rows = await mysql.fetch_all(
        """
        SELECT payload
        FROM agent_event
        WHERE session_id = :session_id AND event_type = 'tool_result'
        ORDER BY seq ASC
        LIMIT :limit
        """,
        {"session_id": session_id, "limit": _MAX_TOOL_HISTORY},
    )

    lines: list[str] = []
    for row in rows:
        data = _as_dict(row.get("payload"))
        if data is None:
            continue
        tool = str(data.get("tool") or "工具")
        summary = " ".join(str(data.get("summary") or "").split())
        if not summary:
            continue
        lines.append(f"- {tool}: {summary[:200]}")
    return lines


def _as_dict(raw: Any) -> dict[str, Any] | None:
    """payload 列是 MySQL 的 JSON 类型：不同驱动可能给 str 也可能给 dict，两种都认。"""
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, (str, bytes, bytearray)):
        try:
            data = json.loads(raw)
        except Exception:
            return None
        return data if isinstance(data, dict) else None
    return None


async def _load_trigger_log(project_id: str, log_id: str) -> LogEntry | None:
    """按 id 取触发日志。

    ★ 一定是按 _id 直取，不要写成"先查前 N 条再在内存里比对 id" ——
      触发日志的行号经常排在很后面（比如第 676 行、第 805 行），
      不在前 N 条里就返回 None，于是触发日志这一段**悄悄从上下文里消失**，
      模型只能凭空猜，而且没有任何报错提示。
    """
    if not project_id or not log_id:
        return None
    return await es.get_log_by_id(project_id=project_id, log_id=log_id)
