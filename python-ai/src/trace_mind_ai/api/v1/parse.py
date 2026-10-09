"""POST /v1/parse —— 批量模板提取。

Java 侧调用点：采集攒批 / 导入每 500 行时。
对应 Java 代码：PythonClient.parse()

★★ 这个接口最关键的一条约束：**返回列表必须与输入等长同序**。
   Java 侧是按下标取的：
       parsed.add(new ParseResult(str(m.get("template")), str(m.get("clusterId"))))
   少一条或者顺序错位，模板就会写到错误的日志行上 ——
   而且**不会报错**，只是数据悄悄错了。
   所以提取失败的行必须占位返回 {"template": null}，不能跳过。
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Body

from trace_mind_ai.core.logging import get_logger
from trace_mind_ai.domain.templating import get_miner_pool
from trace_mind_ai.schemas.parse import ParseRequest, ParseResponse, ParseResult

router = APIRouter(tags=["parse"])
log = get_logger(__name__)


@router.post("/parse")
async def parse(
    payload: Annotated[ParseRequest, Body()],
) -> ParseResponse:
    """批量提取模板。

    请求体：{projectId, logs}（Java 侧 PythonClient.parse() 发的就是这两个字段）。

    ★ projectId 用于**按项目隔离模板池** —— 不同服务的日志格式完全不同，
      共用一个 Drain3 实例会让 A 项目的模板被 B 项目的日志污染。
      projectId 为空时回落到全局池（单项目场景够用）。

    ⚠️ 曾经踩过的坑：ParseRequest 里漏了 projectId 字段，但因为配了 extra="ignore"，
       请求照样能进来，直到这里 `payload.projectId` 抛 AttributeError → **整个接口 500**；
       Java 侧捕获后静默降级成"模板全 null"，从表象上完全看不出是这个原因。
    """
    if not payload.logs:
        return ParseResponse(results=[])

    extracted = await get_miner_pool().extract(payload.logs, project_id=payload.projectId)

    # ★ 防御性断言：长度不一致会静默写错数据，这里宁可主动报错
    if len(extracted) != len(payload.logs):
        log.error(
            "parse.length_mismatch",
            input=len(payload.logs),
            output=len(extracted),
        )
        extracted = extracted[: len(payload.logs)]
        extracted.extend([(None, None)] * (len(payload.logs) - len(extracted)))

    return ParseResponse(
        results=[ParseResult(template=t, clusterId=c) for t, c in extracted]
    )