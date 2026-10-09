"""请求中间件：trace id 绑定 + 访问日志 + 耗时。

★ trace id 的来源：
  Java 侧不会主动发 trace 头（PythonClient 没设），
  但**诊断请求体里有 sessionId** —— 那就是天然的 trace id。
  所以这里做两级：优先取请求头，没有就用路径里能看出来的，
  实在没有就生成一个并回写响应头（方便和 Java 日志对不上时人工串联）。
"""

from __future__ import annotations

import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from trace_mind_ai.core.logging import bind_trace, clear_trace, get_logger

log = get_logger(__name__)

TRACE_HEADER = "X-Trace-Id"

#: 这些路径每几秒就被探活一次，记日志纯属噪音
_SILENT_PATHS = frozenset({"/v1/health"})


class TraceMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        trace_id = (
            request.headers.get(TRACE_HEADER)
            or request.headers.get("X-Request-Id")
            or uuid.uuid4().hex[:16]
        )
        bind_trace(trace_id, method=request.method, path=request.url.path)

        started = time.perf_counter()
        status_code = 500
        try:
            response = await call_next(request)
            status_code = response.status_code
            response.headers[TRACE_HEADER] = trace_id
            return response
        finally:
            cost_ms = int((time.perf_counter() - started) * 1000)
            if request.url.path not in _SILENT_PATHS:
                log.info(
                    "http.request",
                    status=status_code,
                    cost_ms=cost_ms,
                    client=request.client.host if request.client else None,
                )
            # ★ 必须清理：contextvars 是绑在任务上的，
            #   不清理的话同一个 worker 处理后续请求时会带着上一个的 trace_id
            clear_trace()