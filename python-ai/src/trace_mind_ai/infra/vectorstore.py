"""Chroma 向量库 —— 历史诊断案例召回。

★ 这里只做「案例」的语义检索，不碰日志。
  日志检索走 ES 关键词，理由（值得记住，面试会问）：
    · 日志有强结构化信号（类名 / 错误码 / traceId），**精确匹配召回更准**
    · 日志重复度极高、噪声大（时间戳、线程名、traceId 占一半），
      embedding 出来都很像，**区分度低**
    · 关键词命中**可解释**（能说清为什么召回这条），向量只能说"距离近"
  而案例是自然语言（"订单服务在支付回调时超时"），
  需要的是语义相似 —— 这才是向量该干的活。

★ 召回做两层，别一上来就用向量：
    第 1 层：模板 ID / 异常类型 精确匹配  ← 命中即用，0 成本 0 延迟
    第 2 层：向量相似度                   ← 语义兜底
  同一类异常（同模板 ID）的处置方案基本就是同一个，根本不需要向量。
"""

from __future__ import annotations

import asyncio
from typing import Any

from trace_mind_ai.core.config import get_settings
from trace_mind_ai.core.logging import get_logger

log = get_logger(__name__)

_client: Any = None
_collection: Any = None


def _get_collection() -> Any:
    """取 Chroma collection（同步 API）。

    ★ Chroma 的 Python 客户端是同步的，所以调用处必须用 asyncio.to_thread。
      直接在 async 函数里调会阻塞事件循环。
    """
    global _client, _collection
    if _collection is not None:
        return _collection

    try:
        import chromadb
        from chromadb.config import Settings as ChromaConfig

        settings = get_settings().chroma
        _client = chromadb.PersistentClient(
            path=settings.persist_dir,
            settings=ChromaConfig(anonymized_telemetry=False),
        )
        # get_or_create：首次运行会自动建 collection
        _collection = _client.get_or_create_collection(
            name=settings.collection,
            # 默认用内置的 ONNX 小模型（all-MiniLM-L6-v2），零额外依赖。
            # 中文效果一般；要更好的中文向量，装 sentence-transformers 并在
            # 这里换成 from chromadb.utils.embedding_functions import SentenceTransformerEmbeddingFunction
            metadata={"hnsw:space": "cosine"},
        )
        log.info("chroma.collection_ready", collection=settings.collection)
    except Exception as exc:
        log.warning("chroma.init_failed", error=type(exc).__name__, detail=str(exc)[:200])
        _collection = None

    return _collection


def _query_sync(description: str, top_k: int) -> list[dict[str, Any]]:
    collection = _get_collection()
    if collection is None:
        return []

    try:
        result = collection.query(query_texts=[description], n_results=top_k)
    except Exception as exc:
        log.warning("chroma.query_failed", error=type(exc).__name__)
        return []

    documents = (result.get("documents") or [[]])[0]
    metadatas = (result.get("metadatas") or [[]])[0]
    distances = (result.get("distances") or [[]])[0]

    hits: list[dict[str, Any]] = []
    for doc, meta, dist in zip(documents, metadatas, distances, strict=False):
        hits.append({
            "content": doc,
            "metadata": meta or {},
            # cosine 距离 → 相似度。距离 0 是完全相同
            "similarity": round(max(0.0, 1.0 - float(dist)), 3),
        })
    return hits


async def search_similar_cases(description: str, *, top_k: int = 3) -> list[dict[str, Any]]:
    """语义检索相似案例。"""

    def run() -> list[dict[str, Any]]:
        return _query_sync(description, top_k)

    # ★ 用 wait_for 加超时：Chroma 首次加载模型可能卡住，
    #   不能让诊断链路无限等
    try:
        return await asyncio.wait_for(asyncio.to_thread(run), timeout=8.0)
    except TimeoutError:
        log.warning("chroma.query_timeout", top_k=top_k)
        return []


async def match_by_template(template_id: str, *, top_k: int = 3) -> list[dict[str, Any]]:
    """第 1 层召回：按模板 ID 精确匹配。

    这条路径**不花 embedding 成本、不产生延迟**，而且结果确定。
    所以布局是「先走它，没命中才走向量」。
    """

    def run() -> list[dict[str, Any]]:
        collection = _get_collection()
        if collection is None:
            return []
        try:
            result = collection.get(where={"template_id": template_id}, limit=top_k)
        except Exception as exc:
            log.warning("chroma.get_failed", error=type(exc).__name__)
            return []
        docs = result.get("documents") or []
        metas = result.get("metadatas") or []
        return [
            {"content": d, "metadata": m or {}, "similarity": 1.0}
            for d, m in zip(docs, metas, strict=False)
        ]

    try:
        return await asyncio.wait_for(asyncio.to_thread(run), timeout=5.0)
    except TimeoutError:
        return []


async def upsert_case(
    *,
    case_id: str,
    content: str,
    template_id: str,
    exception_type: str,
    root_cause: str,
) -> bool:
    """写入一条案例。诊断成功时调用，让案例库自己长起来。"""

    def run() -> bool:
        collection = _get_collection()
        if collection is None:
            return False
        collection.upsert(
            ids=[case_id],          # ★ upsert 而非 add：同一 case 重复写不会报错
            documents=[content],
            metadatas=[{
                "template_id": template_id,
                "exception_type": exception_type,
                "root_cause": root_cause[:500],
            }],
        )
        return True

    try:
        return await asyncio.wait_for(asyncio.to_thread(run), timeout=8.0)
    except Exception as exc:
        log.warning("chroma.upsert_failed", error=type(exc).__name__)
        return False


async def count() -> int:
    def run() -> int:
        collection = _get_collection()
        return collection.count() if collection is not None else 0

    try:
        return await asyncio.to_thread(run)
    except Exception:
        return 0