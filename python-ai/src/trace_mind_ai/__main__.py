# src/trace_mind_ai/__main__.py
"""支持 `python -m trace_mind_ai` 直接启动（等价于 uvicorn 命令）。

★ reload 只在非生产环境开：热重载会监视文件系统，
  生产环境既浪费资源，又可能因为配置文件被改而意外重启。
"""

from __future__ import annotations

import uvicorn

from trace_mind_ai.core.config import get_settings


def main() -> None:
    settings = get_settings()
    uvicorn.run(
        "trace_mind_ai.main:app",
        host=settings.server.host,
        port=settings.server.port,
        reload=not settings.is_production,
        log_level=settings.log.level.lower(),
        # 不设 workers：本服务有进程内状态（Drain3 模板、Chroma 客户端），
        # 多 worker 会让每个进程各持一份，内存翻倍且状态不一致。要扩就多起容器。
        workers=1,
        timeout_keep_alive=30,
    )


if __name__ == "__main__":
    main()