"""Prometheus 指标。

★ 三个必须有的指标，理由：
  · diagnosis_total     —— 一眼看出**当前诊断成功率**。掉下去就是事故
  · duration_seconds    —— P95 超过 30 秒说明该优化工具或裁剪上下文了
  · llm_tokens_total    —— **成本直接等于这个数**，接告警（比"账单超了"早知道一天）

埋点位置：
  diagnosis_total / duration  → api/v1/diagnose.py 的 _event_stream 里
  llm_tokens_total            → agents/diagnosis.py 的 TokenUsageMiddleware.record 附近
"""

from __future__ import annotations

from prometheus_client import Counter, Gauge, Histogram

#: 诊断结果分布。result 取 success / rule_hit / error / timeout
DIAGNOSIS_TOTAL = Counter(
    "tracemind_diagnosis_total",
    "诊断次数（按结果类型）",
    ["result"],
)

#: 端到端耗时。分桶刻意做得比较细：
#: 规则命中应该在 1 秒内，Agent 路径在 10~60 秒，我们要能区分这两类
DIAGNOSIS_DURATION = Histogram(
    "tracemind_diagnosis_duration_seconds",
    "诊断端到端耗时",
    buckets=(0.5, 1, 2, 5, 10, 20, 30, 60, 120, 180),
)

#: token 消耗 —— 成本指标
LLM_TOKENS = Counter(
    "tracemind_llm_tokens_total",
    "大模型 token 消耗",
    ["kind"],          # prompt / completion
)

#: 工具调用分布
TOOL_CALLS = Counter(
    "tracemind_tool_calls_total",
    "工具调用次数",
    ["tool"],
)

#: 依赖健康状态（0/1）。给告警用 —— 比盯着日志更容易发现"悄悄挂了"
DEPENDENCY_UP = Gauge(
    "tracemind_dependency_up",
    "依赖可用性（1=可用，0=不可用）",
    ["dependency"],
)


def render() -> tuple[bytes, str]:
    """给 /metrics 端点用。"""
    from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

    return generate_latest(), CONTENT_TYPE_LATEST