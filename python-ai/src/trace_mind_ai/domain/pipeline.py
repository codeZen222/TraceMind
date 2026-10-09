"""三阶段诊断编排。

    ┌─ 阶段 1 ─────────┐   ┌─ 阶段 2 ────────┐   ┌─ 阶段 3 ──────────┐
    │ 模板提取         │ → │ 规则匹配        │ → │ Agent（工具 + LLM）│
    │ Drain3           │   │ 5 个分析器      │   │ LangChain          │
    │ 0 token          │   │ 0 token         │   │ 花钱，但有上限     │
    └──────────────────┘   └─────────────────┘   └────────────────────┘
                                  │
                            命中 → 直接给结论，结束（省掉阶段 3）

★ 这个分层的核心价值是**成本**：线上大多数告警是重复的已知问题，
  规则命中就结束了，一次 LLM 都不用调。
  「先规则后模型」不是省事，是**成本意识的架构设计**。
"""

from __future__ import annotations

import time
from collections.abc import AsyncIterator

from trace_mind_ai.core.config import get_settings
from trace_mind_ai.core.logging import get_logger
from trace_mind_ai.domain.rules.base import RuleContext
from trace_mind_ai.domain.rules.registry import get_registry
from trace_mind_ai.domain.templating import get_miner_pool
from trace_mind_ai.schemas.event import DiagnosisEvent
from trace_mind_ai.schemas.log import LogEntry

log = get_logger(__name__)

STAGE_TEMPLATE = (1, "模板提取")
STAGE_RULE = (2, "规则匹配")
STAGE_AGENT = (3, "Agent 分析")


class DiagnosisPipeline:
    """只负责「阶段 1 + 阶段 2」以及阶段 3 的调度。

    阶段 3 的具体实现在 agents/diagnosis.py，通过依赖注入传进来 ——
    这样本模块不 import langchain，单元测试可以注入一个假的 agent。
    """

    def __init__(self) -> None:
        self._registry = get_registry()

    async def run(
        self,
        *,
        project_id: str,
        trigger_log: LogEntry,
        neighbors: list[LogEntry],
        agent_runner: "AgentRunnerProtocol",
    ) -> AsyncIterator[DiagnosisEvent]:
        """跑完整条链路，产出事件流。"""
        depth = 0

        # ══════════════ 阶段 1：模板提取 ══════════════
        stage_no, stage_name = STAGE_TEMPLATE
        yield DiagnosisEvent.stage_running(stage_no, stage_name)
        started = time.perf_counter()

        lines = [trigger_log.rawText or ""] + [n.rawText or "" for n in neighbors]
        templates: list[str] = []
        try:
            extracted = await get_miner_pool().extract(lines, project_id=project_id)
            templates = [t for t, _ in extracted if t]
            if extracted:
                trigger_log.template = extracted[0][0]
                trigger_log.clusterId = extracted[0][1]
        except Exception as exc:
            # 模板提取失败不该中断诊断 —— 规则和 Agent 都能在没有模板时工作
            log.warning("pipeline.templating_failed", error=str(exc)[:200])

        yield DiagnosisEvent.stage_done(
            stage_no, stage_name, int((time.perf_counter() - started) * 1000)
        )

        # ══════════════ 阶段 2：规则匹配 ══════════════
        stage_no, stage_name = STAGE_RULE
        yield DiagnosisEvent.stage_running(stage_no, stage_name)
        started = time.perf_counter()

        ctx = RuleContext(
            project_id=project_id,
            trigger_log=trigger_log,
            templates=templates,
            neighbors=neighbors,
        )
        rule_match = self._registry.match(ctx)

        if rule_match is not None:
            yield DiagnosisEvent.rule_hit(
                rule_match.analyzer,
                rule_match.solution,
                int((time.perf_counter() - started) * 1000),
            )
            yield DiagnosisEvent.stage_done(
                stage_no, stage_name, int((time.perf_counter() - started) * 1000)
            )
            # ★ 规则命中就直接出结论，**不进阶段 3、不花 token**
            yield DiagnosisEvent.result(
                root_cause=f"[{rule_match.analyzer}] {rule_match.solution.splitlines()[0]}",
                evidence=rule_match.evidence,
                suggestion=rule_match.solution,
                confidence=rule_match.confidence,
            )
            log.info("pipeline.finished_by_rule", analyzer=rule_match.analyzer, depth=depth)
            return

        yield DiagnosisEvent.stage_done(
            stage_no, stage_name, int((time.perf_counter() - started) * 1000)
        )

        # ══════════════ 阶段 3：Agent ══════════════
        stage_no, stage_name = STAGE_AGENT
        yield DiagnosisEvent.stage_running(stage_no, stage_name)
        started = time.perf_counter()

        try:
            async for event in agent_runner(project_id=project_id, trigger_log=trigger_log,
                                            templates=templates, neighbors=neighbors):
                yield event
        except Exception as exc:
            log.error("pipeline.agent_failed", error=str(exc)[:300])
            yield DiagnosisEvent.error(stage_no, f"Agent 分析失败: {type(exc).__name__}")
            return

        yield DiagnosisEvent.stage_done(
            stage_no, stage_name, int((time.perf_counter() - started) * 1000)
        )

    @staticmethod
    def budget_guard(started_at: float) -> bool:
        """单轮预算检查。返回 True 表示还有余量。

        与 Java 侧采集模块的「单轮处理预算」是同一套思路：
        防止日志量突然暴涨时，线程长时间困在一轮处理里。
        """
        budget = get_settings().budget
        elapsed = time.perf_counter() - started_at
        if elapsed > budget.max_duration_s:
            log.warning("pipeline.budget_exceeded", elapsed_s=round(elapsed, 2),
                        limit_s=budget.max_duration_s)
            return False
        return True


class AgentRunnerProtocol:
    """Agent 运行器的协议（结构化子类型）。

    定义成协议而不是抽象基类，是为了让 pipeline 不 import agents 模块 ——
    依赖方向保持 domain → 不依赖任何上层。
    """

    def __call__(
        self,
        *,
        project_id: str,
        trigger_log: LogEntry,
        templates: list[str],
        neighbors: list[LogEntry],
    ) -> AsyncIterator[DiagnosisEvent]:  # pragma: no cover - 仅用于类型
        ...