"""工具注册表。

★ 工具名必须与 Java 侧 DiagnosisEventVO 注释里列的一致：
    search_logs | search_similar_cases | match_exception_rules
    count_error_frequency | read_source_code | check_project_health
  前端 ToolCallCard 是按这些名字做图标和文案映射的，**别改**。
"""

from __future__ import annotations

from langchain_core.tools import BaseTool

from trace_mind_ai.tools.error_frequency import count_error_frequency
from trace_mind_ai.tools.exception_rules import match_exception_rules
from trace_mind_ai.tools.project_health import check_project_health
from trace_mind_ai.tools.search_logs import search_logs
from trace_mind_ai.tools.similar_cases import search_similar_cases
from trace_mind_ai.tools.source_code import read_source_code

#: 传给 create_agent 的工具列表。
#: 顺序会影响模型看到的工具描述顺序，把最可能用上的放前面。
ALL_TOOLS: list[BaseTool] = [
    search_logs,                 # 最常用
    count_error_frequency,       # 廉价，用来探规模
    search_similar_cases,
    match_exception_rules,
    check_project_health,
    read_source_code,            # 开销最大，放最后
]

TOOL_NAMES: frozenset[str] = frozenset(t.name for t in ALL_TOOLS)

__all__ = ["ALL_TOOLS", "TOOL_NAMES"]