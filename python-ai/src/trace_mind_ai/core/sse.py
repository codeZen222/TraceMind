"""SSE 帧编码 —— 这个文件虽小，但它是对 Java 的硬契约。

Java 侧 `PythonClient.diagnose()` 用 `WebClient.bodyToFlux(String.class)` 读，
它期望的形状是：

    data: {"type":"stage",...}\\n\\n
    data: {"type":"token","content":"根据"}\\n\\n
    data: [DONE]\\n\\n

三个不能改的细节：
  ① 每帧必须以 "data: " 开头，以**两个**换行结尾（少一个换行 Java 要等到流结束才吐出）
  ② 结束帧固定 "data: [DONE]" —— Java 那边写死了 `!"[DONE]".equals(payload)`
  ③ **绝不 strip 内容** —— token 里的前导空格是英文单词的分隔符
"""

from __future__ import annotations

import json
from typing import Any, Final

#: 结束帧。Java 侧靠它判断流结束，改这个字面量必须同步改 PythonClient.java
DONE_FRAME: Final[str] = "data: [DONE]\n\n"

#: SSE 响应头。缺了这个 Java 的 WebClient 不会按流处理。
SSE_HEADERS: Final[dict[str, str]] = {
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
    # 关掉 nginx 缓冲：否则事件会被攒在反代里，实时性全丢
    "X-Accel-Buffering": "no",
}


def encode(payload: dict[str, Any] | str) -> str:
    """编码一个 SSE 帧。

    dict 走 JSON（ensure_ascii=False：中文原样输出，Jackson 读 UTF-8 没问题，
    而且比 \\uXXXX 转义体积小得多）。str 视为已经是 JSON 串。
    """
    if isinstance(payload, str):
        body = payload
    else:
        body = json.dumps(
            payload,
            ensure_ascii=False,
            separators=(",", ":"),      # 去掉空格，省流量
            default=str,                 # datetime / Decimal 兜底
        )
    # 兜底：真的出现裸换行会把这个帧劈成两帧。
    # （json.dumps 的输出不会有裸换行，这里主要防字符串直传的情况）
    body = body.replace("\r", " ").replace("\n", " ")
    return f"data: {body}\n\n"