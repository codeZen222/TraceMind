"""POST /v1/diagnose —— 三阶段诊断，SSE 事件流。

Java 侧调用点：DiagnosisServiceImpl.runDiagnosis()
对应 Java 代码：PythonClient.diagnose()

★★★ 三条硬契约 ★★★
 ① 响应必须是 text/event-stream，且**每帧一个事件**
 ② 最后必须有 `data: [DONE]`（Java 侧 filter 掉它作为流结束标记）
 ③ **绝不能在流开始前抛异常**：
    Java 侧一旦拿到 200 + event-stream，就进入了消费循环；
    如果这时 Python 报错断开，Java 只能看到"流提前结束"，
    然后它的 finalizeIfRunning 会把会话标成 FAILED ——
    用户看到的是一条没有原因的失败。
    **所以所有异常都要在流内部转换成 error 事件。**
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import APIRouter, Body
from fastapi.responses import StreamingResponse

from trace_mind_ai.agents.diagnosis import run_diagnosis_agent
from trace_mind_ai.api.deps import PipelineDep, TokenDep
from trace_mind_ai.core.logging import bind_trace, get_logger
from trace_mind_ai.core.sse import DONE_FRAME, SSE_HEADERS, encode
from trace_mind_ai.domain.pipeline import DiagnosisPipeline
from trace_mind_ai.infra import es
from trace_mind_ai.schemas.event import DiagnosisEvent
from trace_mind_ai.schemas.log import LogEntry
from pydantic import BaseModel, ConfigDict

router = APIRouter(tags=["diagnose"])
log = get_logger(__name__)

#: 拉多少条相邻日志作为初始上下文。
#: 为什么是 10：再多对判断帮助不大，但会显著增加首轮 prompt 长度。
_NEIGHBOR_LIMIT = 10


class DiagnoseRequest(BaseModel):
    """请求体。字段名严格对齐 PythonClient.diagnose() 的 Map.of(...)。"""

    model_config = ConfigDict(extra="ignore")

    projectId: str  # noqa: N815
    sessionId: str  # noqa: N815
    triggerLog: LogEntry  # noqa: N815


@router.post("/diagnose")
async def diagnose(
    payload: Annotated[DiagnoseRequest, Body()],
    pipeline: PipelineDep,
    _token: TokenDep,
) -> StreamingResponse:
    return StreamingResponse(
        _event_stream(payload, pipeline),
        media_type="text/event-stream",
        headers=SSE_HEADERS,
    )


async def _event_stream(
    payload: DiagnoseRequest, pipeline: DiagnosisPipeline
) -> AsyncIterator[str]:
    """生成 SSE 帧。

    ★ 整个函数体被 try/except 包住 —— 任何未预料的异常都要变成一条
      error 事件，而不是让连接断掉。
    """
    bind_trace(payload.sessionId, project_id=payload.projectId)
    log.info("diagnose.start", log_id=payload.triggerLog.id, level=payload.triggerLog.level)

    try:
        # 先拉相邻日志：比让 Agent 自己调工具查更省一次往返
        neighbors = await _load_neighbors(payload)

        async for event in pipeline.run(
            project_id=payload.projectId,
            trigger_log=payload.triggerLog,
            neighbors=neighbors,
            agent_runner=_make_runner(payload.sessionId),
        ):
            yield event.to_frame()

    except Exception as exc:
        log.exception("diagnose.failed", error=type(exc).__name__)
        # 阶段号给 0：表示"还没进入任何阶段就失败了"
        yield encode(DiagnosisEvent.error(0, f"诊断服务异常: {type(exc).__name__}").model_dump(
            exclude_none=True
        ))
    finally:
        # ★ 无论成功失败都要发结束帧。
        #   不发的话 Java 侧的 Flux 会一直等，直到超时（120 秒）才收尾 ——
        #   用户要盯着转圈两分钟。
        yield DONE_FRAME
        log.info("diagnose.stream_closed")


async def _load_neighbors(payload: DiagnoseRequest) -> list[LogEntry]:
    """拉触发日志前后各 10 分钟内的日志。

    失败返回空列表 —— 上下文少一点，诊断照样能跑。
    """
    from datetime import datetime, timedelta

    trigger = payload.triggerLog
    start_ts = end_ts = None
    if trigger.timestamp:
        try:
            ts = datetime.fromisoformat(trigger.timestamp.replace("Z", "+00:00"))
            start_ts = (ts - timedelta(minutes=10)).isoformat()
            end_ts = (ts + timedelta(minutes=10)).isoformat()
        except ValueError:
            pass

    try:
        entries = await es.search_logs(
            project_id=payload.projectId,
            keyword=None,
            level=None,
            start_ts=start_ts,
            end_ts=end_ts,
            size=_NEIGHBOR_LIMIT,
        )
    except Exception as exc:
        log.warning("diagnose.neighbors_failed", error=type(exc).__name__)
        return []

    # 排除触发日志自己（它的信息已经在 trigger_log 里单独给了）
    return [e for e in entries if e.id != trigger.id]


def _make_runner(session_id: str):
    """把 session_id 绑定进 agent runner。"""

    async def runner(
        *, project_id: str, trigger_log: LogEntry, templates: list[str], neighbors: list[LogEntry]
    ) -> AsyncIterator[DiagnosisEvent]:
        async for event in run_diagnosis_agent(
            project_id=project_id,
            session_id=session_id,
            trigger_log=trigger_log,
            templates=templates,
            neighbors=neighbors,
        ):
            yield event

    return runner