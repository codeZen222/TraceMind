"""规则注册表。"""

from __future__ import annotations

from collections.abc import Sequence

from trace_mind_ai.core.logging import get_logger
from trace_mind_ai.domain.rules.analyzers import DEFAULT_ANALYZERS
from trace_mind_ai.domain.rules.base import BaseAnalyzer, RuleContext, RuleMatch

log = get_logger(__name__)


class AnalyzerRegistry:
    """按顺序尝试所有分析器，第一个命中的胜出。"""

    def __init__(self, analyzer_types: Sequence[type[BaseAnalyzer]] | None = None) -> None:
        types = list(analyzer_types) if analyzer_types is not None else list(DEFAULT_ANALYZERS)
        self._analyzers: list[BaseAnalyzer] = [cls() for cls in types]

    @property
    def analyzers(self) -> Sequence[BaseAnalyzer]:
        return tuple(self._analyzers)

    def match(self, ctx: RuleContext) -> RuleMatch | None:
        """返回第一个命中。

        ★ 为什么"第一个命中就返回"而不是"全部匹配再挑最好的"：
          · 规则是确定性的，多条命中的情况说明规则区分度不够，
            这时应该去调规则而不是搞打分排序
          · 早返回省时间，而且行为**可预测**（给定输入永远同一个结果）
            这在排查时很重要 —— 你不需要怀疑"这次为什么选了另一条"
        """
        for analyzer in self._analyzers:
            try:
                result = analyzer.match(ctx)
            except Exception as exc:
                # 单条规则写错了不能让整个诊断挂掉
                log.error(
                    "rule.analyzer_crashed", analyzer=analyzer.name, error=str(exc)[:200]
                )
                continue
            if result is not None:
                log.info("rule.hit", analyzer=result.analyzer, confidence=result.confidence)
                return result

        log.debug("rule.miss", tried=[a.name for a in self._analyzers])
        return None


#: 单例。规则无状态，可安全共享。
_registry = AnalyzerRegistry()


def get_registry() -> AnalyzerRegistry:
    return _registry