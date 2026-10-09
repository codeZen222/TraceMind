"""异常体系。

一条原则：**能在本地处理的异常不要往上抛；必须上抛的必须带够排查信息。**
"""

from __future__ import annotations


class TraceMindAIError(Exception):
    """所有业务异常的基类。"""

    code: str = "INTERNAL_ERROR"
    http_status: int = 500

    def __init__(self, message: str, *, detail: str | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.detail = detail

    def to_dict(self) -> dict[str, object]:
        payload: dict[str, object] = {"code": self.code, "message": self.message}
        if self.detail:
            payload["detail"] = self.detail
        return payload


class ConfigError(TraceMindAIError):
    """配置缺失或非法。启动期就抛，不留到运行期。"""

    code = "CONFIG_ERROR"


class ContractError(TraceMindAIError):
    """来自 Java 的请求不符合契约（字段缺失、类型不对）。

    这类错误应当**快速失败并返回明确的 4xx**，而不是硬着头皮往下跑 ——
    契约错的时候，跑出来的结果一定是错的。
    """

    code = "CONTRACT_ERROR"
    http_status = 422


class UpstreamError(TraceMindAIError):
    """外部依赖出错。"""

    code = "UPSTREAM_ERROR"
    http_status = 502


class LLMError(UpstreamError):
    """大模型调用失败（超时 / 限流 / 余额不足 / Key 无效）。"""

    code = "LLM_ERROR"


class DependencyUnavailable(UpstreamError):
    """ES / MySQL / Redis / Chroma 不可用。

    ⚠️ 这个异常**不应该被用来中断诊断链路**。
    依赖挂了要走降级分支，只有在「降级也不可行」时才抛它。
    """

    code = "DEPENDENCY_UNAVAILABLE"


class BudgetExceeded(TraceMindAIError):
    """单轮预算用尽（行数或时长）。不是错误，是主动熔断。

    Agent 循环和工具遍历都可能撞到它，撞到就该体面收尾，
    而不是让线程继续困在这一轮里。
    """

    code = "BUDGET_EXCEEDED"