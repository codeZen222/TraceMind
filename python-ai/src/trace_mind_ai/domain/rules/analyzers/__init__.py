"""分析器集合。

★ 新增规则只要两步：
  ① 写一个 BaseAnalyzer 子类
  ② 加进下面的 DEFAULT_ANALYZERS 列表
  顺序有意义 —— 排在前面先匹配，所以「特征越具体」的规则越靠前。
"""

from trace_mind_ai.domain.rules.analyzers.connection_refused import ConnectionRefusedAnalyzer
from trace_mind_ai.domain.rules.analyzers.null_pointer import NullPointerAnalyzer
from trace_mind_ai.domain.rules.analyzers.oom import OutOfMemoryAnalyzer
from trace_mind_ai.domain.rules.analyzers.sql_error import SqlErrorAnalyzer
from trace_mind_ai.domain.rules.analyzers.timeout import TimeoutAnalyzer

#: 匹配顺序：具体 → 宽泛
#: OOM 和连接错误特征最明确，放前面；NPE 最泛，放最后兜底
DEFAULT_ANALYZERS: tuple[type, ...] = (
    OutOfMemoryAnalyzer,          # "OutOfMemoryError" 极明确
    ConnectionRefusedAnalyzer,    # "Connection refused" 极明确
    SqlErrorAnalyzer,             # 数据库错误码明确
    TimeoutAnalyzer,              # "timeout" 较泛但仍有指向性
    NullPointerAnalyzer,          # 最泛，兜底
)

__all__ = [
    "DEFAULT_ANALYZERS",
    "ConnectionRefusedAnalyzer",
    "NullPointerAnalyzer",
    "OutOfMemoryAnalyzer",
    "SqlErrorAnalyzer",
    "TimeoutAnalyzer",
]