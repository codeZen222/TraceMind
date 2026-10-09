"""诊断事件契约 —— 与 Java 侧 DiagnosisEventVO 逐字段对齐。

对应文件：TraceMind/src/main/java/org/example/tracemind/pojo/vo/DiagnosisEventVO.java

★★★ 三条不能破的规矩 ★★★

① 序列化必须 exclude_none=True
   Java 那边有 @JsonInclude(NON_NULL)，null 字段会**整个消失**。
   Python 这边必须一致，否则前端拿到的 JSON 会多出一堆 null，
   和 mock 数据不逐字一致（前端有些判断是 `'truncated' in event`）。

② 字段名一个字母都不能改
   Java 的 Jackson 反序列化认的是字段名。写成 is_truncated 不会报错，
   只会让 Java 侧 truncated=null → 序列化时字段消失 → 前端少一个标记。
   **这是静默失败，最难查。**

③ 8 类事件挤在一个模型里（和 Java 一样）
   它们的差别只在"带哪些字段"，没有行为差异，前端也是按 type 分支渲染同一个对象。
   拆成 8 个子类会引入多态反序列化配置，收益为零。
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict

from trace_mind_ai.core.sse import encode

EventType = Literal[
    "stage",        # 阶段流转
    "rule_hit",     # 规则命中（不进 LLM）
    "tool_call",    # 工具调用
    "tool_result",  # 工具返回
    "token",        # 流式文本片段
    "thinking",     # 思考过程
    "result",       # 最终结论
    "error",        # 异常
]

StageStatus = Literal["running", "done"]


class DiagnosisEvent(BaseModel):
    """一条诊断事件。字段与 DiagnosisEventVO 一一对应。"""

    model_config = ConfigDict(
        # 空字段不进 JSON —— 对应 Java 的 @JsonInclude(NON_NULL)
        # 通过 model_dump(exclude_none=True) 实现，见 to_frame()
        extra="forbid",   # 多写一个字段直接报错，防手滑
    )

    type: EventType

    # ── stage ──
    stage: int | None = None
    name: str | None = None
    status: StageStatus | None = None
    costMs: int | None = None  # noqa: N815 —— 必须跟 Java 的驼峰

    # ── rule_hit ──
    analyzer: str | None = None
    solution: str | None = None

    # ── tool_call / tool_result ──
    tool: str | None = None
    args: dict[str, Any] | None = None
    summary: str | None = None
    truncated: bool | None = None

    # ── token / thinking ──
    content: str | None = None

    # ── result ──
    rootCause: str | None = None  # noqa: N815
    evidence: list[str] | None = None
    suggestion: str | None = None
    confidence: float | None = None

    # ── error ──
    message: str | None = None

    # ══════════════════════════════════════════════════════
    # 构造器 —— 与 Java 侧的静态工厂方法一一对应
    # 用这些而不是直接 DiagnosisEvent(...)：能防止漏填必填字段
    # ══════════════════════════════════════════════════════

    @classmethod
    def stage_running(cls, stage: int, name: str) -> DiagnosisEvent:
        return cls(type="stage", stage=stage, name=name, status="running")

    @classmethod
    def stage_done(cls, stage: int, name: str, cost_ms: int) -> DiagnosisEvent:
        return cls(type="stage", stage=stage, name=name, status="done", costMs=cost_ms)

    @classmethod
    def rule_hit(cls, analyzer: str, solution: str, cost_ms: int) -> DiagnosisEvent:
        return cls(type="rule_hit", analyzer=analyzer, solution=solution, costMs=cost_ms)

    @classmethod
    def tool_call(cls, tool: str, args: dict[str, Any]) -> DiagnosisEvent:
        return cls(type="tool_call", tool=tool, args=args)

    @classmethod
    def tool_result(
        cls, tool: str, summary: str, truncated: bool, cost_ms: int
    ) -> DiagnosisEvent:
        return cls(
            type="tool_result", tool=tool, summary=summary,
            truncated=truncated, costMs=cost_ms,
        )

    @classmethod
    def token(cls, content: str) -> DiagnosisEvent:
        return cls(type="token", content=content)

    @classmethod
    def thinking(cls, content: str) -> DiagnosisEvent:
        return cls(type="thinking", content=content)

    @classmethod
    def result(
        cls,
        root_cause: str,
        evidence: list[str],
        suggestion: str,
        confidence: float,
    ) -> DiagnosisEvent:
        return cls(
            type="result",
            rootCause=root_cause,
            evidence=evidence,
            suggestion=suggestion,
            # 钳到 [0,1]：模型偶尔会返回 1.2 这种值，落库是 DECIMAL 会报错
            confidence=max(0.0, min(1.0, confidence)),
        )

    @classmethod
    def error(cls, stage: int, message: str) -> DiagnosisEvent:
        return cls(type="error", stage=stage, message=message)

    # ══════════════════════════════════════════════════════

    def to_frame(self) -> str:
        """转成一个 SSE 帧。"""
        return encode(self.model_dump(exclude_none=True))