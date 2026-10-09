"""统一异常处理。

★ 三条原则：
  ① **不要在响应里带堆栈**。堆栈是给日志看的，给调用方看是信息泄漏。
     生产环境连 detail 都不要带，只给一个能对日志的 trace_id。
  ② **错误响应结构统一**，Java 侧才好写通用处理。
  ③ **5xx 必须能被监控捕获** —— 所以这里同时也打了一条 error 日志。
"""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from trace_mind_ai.core.config import get_settings
from trace_mind_ai.core.exceptions import TraceMindAIError
from trace_mind_ai.core.logging import get_logger

log = get_logger(__name__)


def _payload(code: str, message: str, *, detail: str | None = None) -> dict[str, object]:
    body: dict[str, object] = {"code": code, "message": message}
    if detail:
        body["detail"] = detail
    return body


def register_exception_handlers(app: FastAPI) -> None:
    settings = get_settings()
    expose_detail = not settings.is_production     # 生产不返回 detail

    @app.exception_handler(TraceMindAIError)
    async def _handle_domain(request: Request, exc: TraceMindAIError) -> JSONResponse:
        # 4xx 是调用方问题，5xx 是我们问题 —— 分开记级别，告警才不会被噪音淹没
        if exc.http_status >= 500:
            log.error(
                "api.domain_error",
                code=exc.code,
                status=exc.http_status,
                detail=exc.detail,
                path=request.url.path,
            )
        else:
            log.warning("api.client_error", code=exc.code, path=request.url.path)

        return JSONResponse(
            status_code=exc.http_status,
            content=_payload(exc.code, exc.message, detail=exc.detail if expose_detail else None),
        )

    @app.exception_handler(RequestValidationError)
    async def _handle_validation(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        # ★ 请求校验失败 = 契约不一致。这几乎总是意味着 Java 侧改了字段而 Python 没跟上，
        #   所以日志里要打全错误详情 —— 这是排查跨语言契约问题最重要的线索
        log.error(
            "api.validation_failed",
            path=request.url.path,
            errors=exc.errors()[:5],
        )
        return JSONResponse(
            status_code=422,
            content=_payload(
                "CONTRACT_ERROR",
                "请求体不符合契约",
                detail=str(exc.errors()[:3]) if expose_detail else None,
            ),
        )

    @app.exception_handler(StarletteHTTPException)
    async def _handle_http(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content=_payload("HTTP_ERROR", str(exc.detail)),
        )

    @app.exception_handler(Exception)
    async def _handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        """兜底。

        ★ 绝对不能把 str(exc) 直接返回给调用方：
          数据库连接串、文件路径、内部主机名都可能出现在异常信息里。
        """
        log.exception("api.unhandled", path=request.url.path, error=type(exc).__name__)
        return JSONResponse(
            status_code=500,
            content=_payload(
                "INTERNAL_ERROR",
                "服务内部错误",
                detail=f"{type(exc).__name__}: {exc}" if expose_detail else None,
            ),
        )