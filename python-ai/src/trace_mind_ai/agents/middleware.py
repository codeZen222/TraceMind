"""Agent middleware —— 成本与安全闸门。

为什么必须有这几道闸门：
  本地模型烧电，云 API 烧钱。**Agent 循环是乘法级消耗** ——
  每一轮都要把完整的对话历史重新发给模型，
  一个 10 轮的循环，token 量不是线性的 10 倍而是远超。

  「工具调用次数上限」在本地部署时是"防御性设计"，
  换成云 API 后就变成了**止损刚需**。

三道闸门（互相独立，任何一道生效都能止损）：
  ① ToolBudgetMiddleware  —— 工具调用次数（本文实现）
  ② recursion_limit       —— LangGraph 的 superstep 上限（框架内置，见 diagnosis.py）
  ③ wall-clock 检查       —— 总耗时（pipeline.py 的 budget_guard）

★ 关于「计数器放在哪里」——这是最容易被写错、且报错信息完全看不出原因的地方：

  本文的做法：**每次诊断新建一个 middleware 实例**（在 run_diagnosis_agent 里 new），
  计数器放实例属性上。**因为实例不共享，并发请求各算各的，不会串。**

  另一条路是**复用 agent**（把它缓存起来，省掉每次重新编译图的开销）。
  那种情况下计数器**必须放进 agent state**，不能放 middleware 实例属性 ——
  否则所有并发请求共用同一个计数器，A 的调用次数会算到 B 头上，
  表现为**「什么都没干就报超预算」**，而且极难查。

  两条路都对，取舍是：

  | 做法 | 计数器位置 | 代价 |
  |---|---|---|
  | **每次新建实例**（本文） | 实例属性 ✅ | 每次诊断多一次图编译（毫秒级） |
  | 复用 agent + 缓存 | **必须放 agent state** | 需正确使用 state，复杂度更高 |

  本工程选前者 —— **诊断本身要跑十几秒，几毫秒的编译开销不值得为它引入 state 复杂度。**
  判断口诀：**实例是"每请求一个"→ 属性可以；实例"跨请求共享"→ 必须 state。**
"""

from __future__ import annotations

import time
from typing import Any

from langchain.agents.middleware import AgentMiddleware

from trace_mind_ai.core.exceptions import BudgetExceeded
from trace_mind_ai.core.logging import get_logger

log = get_logger(__name__)


class ToolBudgetMiddleware(AgentMiddleware):
    """限制工具调用次数与总时长。

    ⚠️ 一次诊断创建一个实例（不能用单例）—— 它是有状态的计数器。
    """

    def __init__(self, max_calls: int = 6, max_seconds: float = 90.0) -> None:
        super().__init__()
        self.max_calls = max_calls
        self.max_seconds = max_seconds
        self._calls = 0
        self._started = time.monotonic()
        self._tool_usage: list[str] = []

    # ── 同步版 ──

    def wrap_tool_call(self, request: Any, handler: Any) -> Any:
        self._check_budget(request)
        return handler(request)

    # ── 异步版（我们的链路走这条）──

    async def awrap_tool_call(self, request: Any, handler: Any) -> Any:
        self._check_budget(request)
        return await handler(request)

    def _check_budget(self, request: Any) -> None:
        name = _tool_name(request)
        self._calls += 1
        self._tool_usage.append(name)

        if self._calls > self.max_calls:
            log.warning(
                "agent.tool_budget_exceeded",
                calls=self._calls,
                limit=self.max_calls,
                usage=self._tool_usage,
            )
            # 抛异常会让 Agent 立刻中断，由上层转成一条 error 事件。
            # 这比"继续跑下去烧钱"好 —— 超过 6 次调用基本可以断定是循环了。
            raise BudgetExceeded(
                f"工具调用超过上限（{self.max_calls} 次）：{self._tool_usage}"
            )

        elapsed = time.monotonic() - self._started
        if elapsed > self.max_seconds:
            log.warning("agent.time_budget_exceeded", elapsed_s=round(elapsed, 1))
            raise BudgetExceeded(f"Agent 运行超过 {self.max_seconds} 秒")

    @property
    def usage(self) -> list[str]:
        return list(self._tool_usage)


class TokenUsageMiddleware(AgentMiddleware):
    """统计 token 消耗。

    为什么要单独统计：**成本等于 token 数**。
    开发期把它打出来，你能立刻发现"哦原来一个诊断要 8000 token 而不是 800"，
    这种发现往往会直接改变你的 prompt 设计。

    ⚠️ 这里只做统计与告警，不做拦截 —— 拦截交给 ToolBudgetMiddleware。
      两件事分开，各自的逻辑才简单。
    """

    def __init__(self, warn_threshold: int = 20_000) -> None:
        super().__init__()
        self.warn_threshold = warn_threshold
        self.prompt_tokens = 0
        self.completion_tokens = 0

    # ⚠️ after_model 必须保持【同步】。这里曾经同时写了同步 + 异步两个同名的
    #   after_model，Python 里后一个会覆盖前一个 —— 结果框架按同步调用时拿到的是
    #   一个 coroutine 对象，langgraph 抛 `InvalidUpdateError: Expected dict, got
    #   <coroutine object ...>`。而这个 hook 实际上什么都不做（token 统计走 record()，
    #   由 diagnosis.py / chat.py 在 on_chat_model_end 里调用），所以只留一个空实现。
    def after_model(self, state: Any, runtime: Any) -> Any:  # noqa: ARG002
        return None

    def record(self, usage: dict[str, int]) -> None:
        """由 diagnosis.py 在收到 usage 事件时调用。"""
        self.prompt_tokens += int(usage.get("input_tokens") or usage.get("prompt_tokens") or 0)
        self.completion_tokens += int(
            usage.get("output_tokens") or usage.get("completion_tokens") or 0
        )
        total = self.prompt_tokens + self.completion_tokens
        if total > self.warn_threshold:
            log.warning(
                "agent.token_usage_high",
                total=total,
                prompt=self.prompt_tokens,
                completion=self.completion_tokens,
            )

    @property
    def total(self) -> int:
        return self.prompt_tokens + self.completion_tokens


def _tool_name(request: Any) -> str:
    """从各种可能的请求形状里把工具名抠出来。

    不同版本的 ToolCallRequest 结构不一样，这里做兼容 ——
    宁可多写几行兼容代码，也不要因为框架升级就让统计失效。
    """
    for attr in ("tool_call", "tool", "name"):
        value = getattr(request, attr, None)
        if value is None:
            continue
        if isinstance(value, str):
            return value
        name = getattr(value, "name", None)
        if isinstance(name, str):
            return name
    if isinstance(request, dict):
        for key in ("tool_call", "tool", "name"):
            value = request.get(key)
            if isinstance(value, str):
                return value
            if isinstance(value, dict) and isinstance(value.get("name"), str):
                return value["name"]
    return "unknown"