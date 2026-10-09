"""日志模型 —— 与 Java 侧 LogEntryVO 逐字段对齐。

对应文件：pojo/vo/LogEntryVO.java

注意：Java 侧只把 id / projectId / level / rawText / stackTrace 传给 Python
（见 PythonClient.diagnose 的 triggerLog 参数），但工具有时需要完整字段，
所以这里把全部字段都定义上，缺的当 None 处理。
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict


class LogEntry(BaseModel):
    """一条日志。字段名与 LogEntryVO 完全一致。"""

    model_config = ConfigDict(extra="ignore")
    # ↑ 这里用 ignore 而不是 forbid：Java 的 LogEntryVO 可能有新增字段，
    #   旧版 Python 不该因此拒绝请求。契约兼容性上"宽容接收"更安全。

    id: str | None = None
    projectId: str | None = None  # noqa: N815
    #: ISO-8601 字符串，如 2026-09-10T18:29:03.512Z
    timestamp: str | None = None
    level: str | None = None
    rawText: str | None = None  # noqa: N815
    template: str | None = None
    clusterId: str | None = None  # noqa: N815
    filePath: str | None = None  # noqa: N815
    lineNumber: int | None = None  # noqa: N815
    stackTrace: str | None = None  # noqa: N815

    def short(self, limit: int = 200) -> str:
        """给日志和 prompt 用的短表示，避免把整条堆栈塞进去。"""
        text = self.rawText or ""
        if len(text) > limit:
            text = text[:limit] + "…"
        return f"[{self.level or '?'}] {text}"