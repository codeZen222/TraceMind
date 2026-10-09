"""v1 路由聚合。"""

from __future__ import annotations

from fastapi import APIRouter

from trace_mind_ai.api.v1 import chat, diagnose, health, parse

api_router = APIRouter()

# 顺序无所谓（路径不冲突），但按调用频率排，读起来顺
api_router.include_router(health.router)
api_router.include_router(parse.router)
api_router.include_router(diagnose.router)
api_router.include_router(chat.router)