"""连接被拒分析器。

★ 这个规则的价值在于**它几乎总能当场给答案**。
  Connection refused 的根因高度集中，而且大多不需要读代码。
"""

from __future__ import annotations

from trace_mind_ai.domain.rules.base import BaseAnalyzer, RuleContext, RuleMatch

_REFUSED_PATTERNS: tuple[str, ...] = (
    r"Connection refused",
    r"ConnectException",
    r"connection reset by peer",
    r"broken pipe",
    r"no such host|UnknownHostException|Name or service not known",
)

_CAUSES: tuple[tuple[str, str], ...] = (
    (
        r"UnknownHostException|no such host|Name or service not known",
        "**域名解析失败**。不是网络不通，是名字没解析出来。检查："
        "① 配置里的主机名拼写 ② 容器 /etc/hosts 或 CoreDNS ③ K8s 里是否在同一个 Namespace"
        "（跨 Namespace 要写全 `svc.namespace.svc.cluster.local`）",
    ),
    (
        r"connection reset by peer|broken pipe",
        "**连接被对端重置**。TCP 层建好了但被 RST。常见原因："
        "① 对端进程重启 / 崩溃 ② 对端连接数打满主动踢 ③ 中间设备（LB / 防火墙）"
        "因为空闲超时清理了连接，而客户端还拿着旧连接发请求。"
        "如果是 ③，把客户端的 keepalive 或连接最大空闲时间调到 LB 之内。",
    ),
    (
        r"Connection refused",
        "**端口拒绝连接**。TCP SYN 收到 RST —— 说明**能到机器，但端口没人听**。"
        "按这个顺序查：① 目标服务是否在运行（`netstat -ano | grep <port>`）"
        "② 服务监听的地址是不是 127.0.0.1（**最常见：服务只绑了本机，外部连不上**）"
        "③ 端口配置是否一致（改过端口但配置没同步）",
    ),
)

_SOLUTION_HEADER = """\
连接层故障。

"""

_SOLUTION_FOOTER = """

判定技巧（这三条能省很多时间）：
  · Connection refused  → 能到达主机，端口没人听。**问题在服务端进程**
  · Connection timeout  → 到不了主机。**问题在网络 / 安全组 / 地址**
  · Connection reset    → 连上了又被断开。**问题在中间设备或对端容量**
三者指向完全不同的方向，别混。"""


class ConnectionRefusedAnalyzer(BaseAnalyzer):
    name = "ConnectionRefusedAnalyzer"
    description = "连接故障（拒绝 / 重置 / 解析失败）"
    confidence = 0.88

    def match(self, ctx: RuleContext) -> RuleMatch | None:
        hit, pattern = self.any_pattern(_REFUSED_PATTERNS, ctx.haystack)
        if not hit:
            return None

        cause = "未识别的连接错误，先看完整的异常类名与目标地址。"
        for pattern_, text in _CAUSES:
            if self.any_pattern((pattern_,), ctx.haystack)[0]:
                cause = text
                break

        hits = [f"命中模式: {pattern}", self.first_line(ctx.haystack)]
        solution = _SOLUTION_HEADER + cause + _SOLUTION_FOOTER
        return self.build_match(ctx, solution, hits)