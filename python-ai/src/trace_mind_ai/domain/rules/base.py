"""规则分析器的基类与上下文。

★ 设计意图：规则引擎是**成本闸门**。
  高频异常的处置方案是确定的，根本不需要花 token 让模型想一遍。
  规则命中就直接给结论 → 省一次 LLM 调用，而且**结果稳定可复现**。
  经验上线上 60~70% 的告警都是重复的已知问题。
"""

from __future__ import annotations

import re
from abc import ABC, abstractmethod
from typing import ClassVar

from pydantic import BaseModel, ConfigDict, Field

from trace_mind_ai.schemas.log import LogEntry


class RuleContext(BaseModel):
    """规则匹配的输入。"""

    model_config = ConfigDict(arbitrary_types_allowed=True)

    project_id: str
    #: 触发诊断的那条日志（必填 —— 规则主要看它）
    trigger_log: LogEntry
    #: 阶段 1 提取出的模板
    templates: list[str] = Field(default_factory=list)
    #: 时间窗内的相邻日志（可选，有些规则需要看"前后发生了什么"）
    neighbors: list[LogEntry] = Field(default_factory=list)

    @property
    def haystack(self) -> str:
        """把所有可匹配的文本拼一起，规则只扫这一处。

        顺序有讲究：原始日志 > 堆栈 > 模板。
        原始日志最完整，模板里的 <*> 反而会丢失信息。
        """
        parts = [
            self.trigger_log.rawText or "",
            self.trigger_log.stackTrace or "",
            " ".join(self.templates),
            " ".join(n.rawText or "" for n in self.neighbors),
        ]
        return "\n".join(p for p in parts if p)


class RuleMatch(BaseModel):
    """规则命中结果。"""

    analyzer: str
    solution: str
    confidence: float
    evidence: list[str] = Field(default_factory=list)


class BaseAnalyzer(ABC):
    """所有分析器的基类。"""

    #: 会写进 rule_hit 事件的 analyzer 字段，**前端用它做展示，别随便改**
    name: ClassVar[str]
    #: 人读的说明，用于文档与调试
    description: ClassVar[str] = ""
    #: 命中后给出的置信度。规则是确定性判断，一般给 0.85~0.95；
    #: 给 1.0 不合适 —— 规则也可能误判，留一点余量
    confidence: ClassVar[float] = 0.9

    @abstractmethod
    def match(self, ctx: RuleContext) -> RuleMatch | None:
        """命中返回 RuleMatch，否则返回 None。"""

    # ── 给子类的工具方法 ──

    @staticmethod
    def any_pattern(patterns: tuple[str, ...], text: str) -> tuple[bool, str]:
        """任一正则命中即算命中。返回 (是否命中, 命中的那个模式)。"""
        for pattern in patterns:
            if re.search(pattern, text, re.IGNORECASE | re.MULTILINE):
                return True, pattern
        return False, ""

    @staticmethod
    def first_line(text: str, limit: int = 160) -> str:
        """取日志的首个有意义行，用作证据展示。"""
        for line in text.splitlines():
            stripped = line.strip()
            if stripped:
                return stripped[:limit]
        return text[:limit]

    def build_match(self, ctx: RuleContext, solution: str, hits: list[str]) -> RuleMatch:
        return RuleMatch(
            analyzer=self.name,
            solution=solution,
            confidence=self.confidence,
            evidence=hits[:5],   # 最多 5 条，多了前端展示不下
        )