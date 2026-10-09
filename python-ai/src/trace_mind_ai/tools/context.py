"""工具运行上下文。

★ 为什么用 contextvars 而不是框架的依赖注入：
  工具的实现在不同 LangChain 版本里，注入上下文的机制换过好几次
  （RunnableConfig.configurable / InjectedToolArg / InjectedState）。
  而 contextvars 是标准库，行为稳定，且天然支持 async 并发隔离。
  **把易变的框架 API 换成稳定的标准库，是这个文件存在的全部理由。**

  project_id 属于「环境」而不属于「模型该填的参数」——
  让模型填 project_id 的话，它有可能填错（幻觉）或填成别的项目，
  那就是跨项目数据泄漏。**这类参数绝不能交给模型。**
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field

from trace_mind_ai.core.config import get_settings
from trace_mind_ai.core.exceptions import BudgetExceeded
from trace_mind_ai.schemas.log import LogEntry


@dataclass
class ToolContext:
    """一次诊断运行期间的工具上下文。"""

    project_id: str
    session_id: str
    trigger_log: LogEntry
    #: 已执行的工具调用次数（用于预算控制）
    tool_calls: int = 0
    #: 已用工具的统计，诊断结束时可以写进报告
    used_tools: list[str] = field(default_factory=list)

    def consume(self) -> None:
        """记一次工具调用，超预算就抛。"""
        max_calls = 6      # 与 6 类工具对齐：每类最多用一遍，再多基本是循环了
        self.tool_calls += 1
        if self.tool_calls > max_calls:
            raise BudgetExceeded(
                f"工具调用次数超过上限 {max_calls}，疑似循环。已调用: {self.used_tools}"
            )
        if self.tool_calls > get_settings().budget.max_log_rows:  # pragma: no cover
            raise BudgetExceeded("工具调用异常")


_ctx: ContextVar[ToolContext | None] = ContextVar("tool_context", default=None)


@contextmanager
def tool_context(ctx: ToolContext) -> Iterator[ToolContext]:
    """绑定上下文。用 with 保证异常路径也能清理。"""
    token = _ctx.set(ctx)
    try:
        yield ctx
    finally:
        _ctx.reset(token)


def current() -> ToolContext:
    ctx = _ctx.get()
    if ctx is None:
        # 这个异常说明有人绕过了 tool_context 直接调工具 —— 是编码错误，不是运行错误
        raise RuntimeError("工具在 ToolContext 之外被调用，project_id 无法确定")
    return ctx