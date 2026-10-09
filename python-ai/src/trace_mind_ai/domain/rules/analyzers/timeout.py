"""超时分析器。

★ 超时是"症状"不是"病因"。规则能给的是**分类判断**：
    · 连接超时（Connect timed out）   → 网络 / 目标不可达 / 端口不对
    · 读超时（Read timed out）        → 对方处理慢 / 慢 SQL / 下游雪崩
    · 池等待超时（pool exhausted）    → 连接池太小 / 连接泄漏
    · 网关超时（504）                 → 链路里某一跳超时，需要看 traceId
  这四类的排查路径完全不同，分类本身就是价值。
"""

from __future__ import annotations

from trace_mind_ai.domain.rules.base import BaseAnalyzer, RuleContext, RuleMatch

_TIMEOUT_PATTERNS: tuple[str, ...] = (
    r"timeout|timed out|TimeoutException|SocketTimeoutException",
    r"Read timed out",
    r"connect timed out",
    r"504 Gateway Time-out",
)

_CLASSIFY: tuple[tuple[str, str, str], ...] = (
    (
        r"connect timed out|Connection refused|No route to host",
        "连接超时",
        "目标不可达。检查：地址端口是否正确、防火墙 / 安全组、目标服务是否在监听、"
        "DNS 解析（容器里常见）。这类问题**客户端调多少参数都没用**，先解决可达性。",
    ),
    (
        r"read timed out|Read timed out|SocketTimeoutException",
        "读超时",
        "连上了但对方响应太慢。检查：对方是否有慢查询 / 慢下游、"
        "是否需要放大读超时、是否是下游雪崩导致排队。**先看对方的指标再看自己的超时配置**。",
    ),
    (
        r"pool|HikariPool|connection is not available|exhausted",
        "资源池耗尽",
        "连接池被占满。两种可能：① 池子太小（看并发数）② 连接泄漏（借了没还）。"
        "②更危险 —— 加池子只是把爆炸时间往后推。查是否有事务里做了远程调用。",
    ),
    (
        r"504|Gateway Time-out|upstream timed out",
        "网关超时",
        "是链路上某一跳超时，不一定是当前服务。拿 traceId 去看完整调用链，"
        "定位是哪一跳先超的，**别在本服务里盲目加超时**。",
    ),
)

_SOLUTION_TEMPLATE = """\
检测到超时（{category}）。

分类依据：{reason}

{advice}

另外三条通用动作：
  · 确认超时是「偶发」还是「持续」—— 偶发看网络，持续看容量
  · 对齐上下游的超时时间：下游超时 > 上游超时才有意义，
    否则上游早就断了，下游还在傻等（超时时间的「倒三角」）
  · 如果配了重试，确认是不是重试放大了流量（重试风暴）"""


class TimeoutAnalyzer(BaseAnalyzer):
    name = "TimeoutAnalyzer"
    description = "请求超时（连接超时 / 读超时 / 池耗尽 / 网关超时）"
    confidence = 0.85

    def match(self, ctx: RuleContext) -> RuleMatch | None:
        hit, _ = self.any_pattern(_TIMEOUT_PATTERNS, ctx.haystack)
        if not hit:
            return None

        category = "未分类超时"
        reason = "只有超时关键字，没有更具体的特征"
        advice = (
            "先看堆栈里抛超时的是哪个组件（HTTP 客户端 / JDBC / Redis / 消息队列），"
            "再按对应组件查。"
        )

        for pattern, name, text in _CLASSIFY:
            if self.any_pattern((pattern,), ctx.haystack)[0]:
                category, reason, advice = name, name, text
                break

        hits = [f"命中模式: {reason}", self.first_line(ctx.haystack)]
        return self.build_match(
            ctx,
            _SOLUTION_TEMPLATE.format(category=category, reason=reason, advice=advice),
            hits,
        )