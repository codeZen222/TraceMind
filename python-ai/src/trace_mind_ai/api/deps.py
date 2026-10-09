"""依赖注入。

★ Python 侧不需要鉴权（内网调用，Java 的 PythonClient 也不带凭证）。
  但生产环境建议加一个共享令牌 —— 万一网络策略配错，
  至少不会让服务完全裸奔。用 ENABLE_SERVICE_TOKEN 开关控制，
  默认关闭以免影响本地开发。
"""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends, Header

from trace_mind_ai.core.config import Settings, get_settings
from trace_mind_ai.core.exceptions import TraceMindAIError
from trace_mind_ai.domain.pipeline import DiagnosisPipeline


class UnauthorizedError(TraceMindAIError):
    code = "UNAUTHORIZED"
    http_status = 401


def settings_dep() -> Settings:
    return get_settings()


def verify_service_token(
    x_service_token: Annotated[str | None, Header(alias="X-Service-Token")] = None,
) -> None:
    """可选的内部令牌校验。

    默认关闭：本地开发时 Java 和 Python 在同一台机器上，
    加令牌只会让联调更麻烦。生产环境通过环境变量打开。
    """
    settings = get_settings()
    expected = getattr(settings, "service_token", None)
    if not expected:
        return
    if x_service_token != expected:
        raise UnauthorizedError("内部服务令牌校验失败")


_pipeline: DiagnosisPipeline | None = None


def get_pipeline() -> DiagnosisPipeline:
    """诊断流水线（单例）。

    流水线本身无状态（规则注册表是只读的），可以安全共享。
    真正有状态的部分（Agent 的计数器）在每次调用时创建。
    """
    global _pipeline
    if _pipeline is None:
        _pipeline = DiagnosisPipeline()
    return _pipeline


SettingsDep = Annotated[Settings, Depends(settings_dep)]
PipelineDep = Annotated[DiagnosisPipeline, Depends(get_pipeline)]
TokenDep = Annotated[None, Depends(verify_service_token)]