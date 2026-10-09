"""search_similar_cases —— 历史案例召回（两层）。"""

from __future__ import annotations

from langchain_core.tools import tool

from trace_mind_ai.infra import vectorstore
from trace_mind_ai.tools.context import current


@tool
async def search_similar_cases(description: str) -> str:
    """检索历史上解决过的相似问题，看当时的根因和处置方式。

    什么时候用：
      · 已经对问题有了初步判断，想看看有没有现成的处置经验
      · 触发日志的异常类型比较罕见，没有把握
      · 在给出最终建议之前，参考历史结论能显著提高准确性

    什么时候不要用：
      · 还在收集事实阶段（先用 search_logs / count_error_frequency）
      · 想找的是"这个项目的具体日志"而不是"历史经验"

    Args:
        description: 用自然语言描述当前问题，比如"订单服务在支付回调时连接超时"。
                     写得越具体，召回越准。
    """
    ctx = current()
    ctx.consume()
    ctx.used_tools.append("search_similar_cases")

    # ── 第 1 层：模板 ID 精确匹配（零成本、零延迟、结果确定）──
    if ctx.trigger_log.clusterId:
        exact = await vectorstore.match_by_template(ctx.trigger_log.clusterId, top_k=2)
        if exact:
            return _format("模板精确匹配", exact)

    # ── 第 2 层：向量语义兜底 ──
    hits = await vectorstore.search_similar_cases(description, top_k=3)
    if not hits:
        return (
            "历史案例库里没有找到相似的记录。\n"
            "这不代表没有先例 —— 只是案例库还比较小。"
            "请基于当前日志的事实做出判断，并明确标注推断的依据。"
        )
    return _format("语义相似", hits)


def _format(source: str, hits: list[dict[str, object]]) -> str:
    lines = [f"找到 {len(hits)} 条历史案例（召回方式：{source}）：", ""]
    for idx, hit in enumerate(hits, 1):
        meta = hit.get("metadata") or {}
        content = str(hit.get("content") or "")
        similarity = hit.get("similarity")
        lines.append(f"{idx}. 相似度 {similarity}")
        if isinstance(meta, dict):
            if meta.get("exception_type"):
                lines.append(f"   异常类型: {meta['exception_type']}")
            if meta.get("root_cause"):
                lines.append(f"   当时根因: {meta['root_cause']}")
        lines.append(f"   详情: {content[:300]}")
        lines.append("")
    lines.append(
        "⚠️ 注意：历史案例只是参考，**不要直接照搬结论**。"
        "要结合当前项目的实际日志判断是否适用。"
    )
    return "\n".join(lines)