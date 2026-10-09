"""模板提取契约 —— /v1/parse 出入参。

对应 Java 侧：
    Map<String, Object> body = Map.of("logs", logs);     // Java 侧发的
    resp.get("results") → List<Map>                       // Java 侧收的
    → ParseResult(template, clusterId)

★ 关键约束：results 的长度和顺序必须与请求里的 logs **一一对应**。
  Java 侧是按下标取的：
      parsed.add(new ParseResult(str(m.get("template")), str(m.get("clusterId"))))
  少一条或顺序错了，模板就会张冠李戴地写到别的日志行上。
  所以 Python 侧即使某行模板提取失败，也要**占位返回 {template: null}**，
  而不是跳过它。
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class ParseRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    # ★ 必须有这个字段：Java 侧 PythonClient.parse() 发的 body 是
    #     Map.of("projectId", ..., "logs", logs)
    #   而 api/v1/parse.py 里要拿它的值做模板池隔离（按项目分开学模板）。
    #   之前这里漏了它 —— 因为 extra="ignore" 不报错，请求能进来，
    #   但 handler 里 `payload.projectId` 直接 AttributeError → **整个 /v1/parse 一直 500**
    #   → Java 侧静默降级成"模板全 null" → ES 里 template/clusterId 全是 null。
    projectId: str | None = None  # noqa: N815 —— 必须跟 Java 的驼峰一致

    logs: list[str] = Field(default_factory=list, max_length=2000)
    # ↑ 上限 2000：Java 侧每 500 行调一次，留出余量但不至于被塞爆


class ParseResult(BaseModel):
    """单行结果。两个字段都可为 null —— 提取失败时的占位。"""

    template: str | None = None
    clusterId: str | None = None  # noqa: N815


class ParseResponse(BaseModel):
    results: list[ParseResult]