"""模型工厂 —— 全工程**唯一**创建 ChatModel 的地方。

收口在这里的三个理由：
  ① API Key 只在这一个文件读，其他模块拿不到 → 泄漏面最小
  ② 换服务商只改这里（OpenAI 兼容协议，base_url 换掉就行）
  ③ 超时 / 重试 / streaming 统一配置，不会各写各的

⚠️ 本项目**不本地部署模型**。本地 7B 做根因分析质量不够，
   而且调 prompt 要迭代几十次，本地推理太慢。
"""

from __future__ import annotations

import time
from enum import StrEnum
from functools import lru_cache

from langchain_core.messages import HumanMessage
from langchain_openai import ChatOpenAI
from pydantic import BaseModel

from trace_mind_ai.core.config import get_settings
from trace_mind_ai.core.logging import get_logger

log = get_logger(__name__)


class ModelProfile(StrEnum):
    """不同用途用不同的温度。"""

    #: 诊断要稳定可复现 —— 温度低
    DIAGNOSIS = "diagnosis"
    #: 追问要自然 —— 温度稍高
    CHAT = "chat"


_TEMPERATURES: dict[ModelProfile, float] = {
    ModelProfile.DIAGNOSIS: 0.1,
    ModelProfile.CHAT: 0.3,
}


@lru_cache(maxsize=4)
def get_chat_model(profile: ModelProfile = ModelProfile.DIAGNOSIS) -> ChatOpenAI:
    """取模型客户端。

    lru_cache 保证同一 profile 只建一个实例 —— ChatOpenAI 内部持有一个 httpx 连接池，
    每次新建会新建连接池，高频调用下会耗尽文件描述符。
    """
    settings = get_settings().llm
    return ChatOpenAI(
        model=settings.model,
        base_url=settings.base_url,
        api_key=settings.api_key.get_secret_value(),
        temperature=_TEMPERATURES[profile],
        timeout=settings.timeout_s,
        # ★ 这里的重试是 SDK 内建的（覆盖 429 / 5xx / 连接错误）
        max_retries=settings.max_retries,
        streaming=True,
    )


# ══════════════════════════════════════════════════════════
# 探活
# ══════════════════════════════════════════════════════════

_PROBE_CACHE_TTL_S = 30.0
_probe_cache: tuple[float, "LLMHealth"] | None = None


class LLMHealth(BaseModel):
    """给 /v1/health 用。Java 的 PythonClient.extractLlmOk() 读的就是 ok 字段。"""

    ok: bool
    model: str
    latencyMs: int
    detail: str | None = None


async def probe_llm(*, force: bool = False) -> LLMHealth:
    """真实探活：发一个最小请求。

    ★ 为什么不只检查「客户端构造成功」：
      构造成功只说明配置格式对，**不说明 Key 有效、余额够、网络通**。
      而 Java 侧会读健康检查的 llm.ok 来决定要不要降级 —— 报假的好消息
      比报错更糟，前端会以为 AI 可用。

    ★ 为什么加 30 秒缓存：
      Java 会定期轮询探活。每次都真调 LLM = 白烧钱。
      30 秒内的重复探测直接返回上次结果。
    """
    global _probe_cache

    now = time.monotonic()
    if not force and _probe_cache is not None and now - _probe_cache[0] < _PROBE_CACHE_TTL_S:
        return _probe_cache[1]

    settings = get_settings().llm
    start = time.perf_counter()
    try:
        model = get_chat_model()
        # 绑定 max_tokens 限制输出，探活只花几十个 token
        await model.bind(max_tokens=4).ainvoke([HumanMessage(content="ping")])
        health = LLMHealth(
            ok=True,
            model=settings.model,
            latencyMs=int((time.perf_counter() - start) * 1000),
        )
    except Exception as exc:
        health = LLMHealth(
            ok=False,
            model=settings.model,
            latencyMs=int((time.perf_counter() - start) * 1000),
            detail=f"{type(exc).__name__}: {str(exc)[:180]}",
        )
        log.warning("llm.probe_failed", error=type(exc).__name__, detail=str(exc)[:200])

    _probe_cache = (now, health)
    return health