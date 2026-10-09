"""OOM / 内存问题分析器。

为什么值得单独一条规则：OOM 的处理方式和普通异常完全不同 ——
它不是"代码写错了"，而是**容量 / 生命周期问题**，处置动作（扩容、加限制、
找泄漏点）跟改代码是两码事。规则命中的价值是**快速分流**。
"""

from __future__ import annotations

from trace_mind_ai.domain.rules.base import BaseAnalyzer, RuleContext, RuleMatch

_OOM_PATTERNS: tuple[str, ...] = (
    r"OutOfMemoryError",
    r"Java heap space",
    r"GC overhead limit exceeded",
    r"Metaspace",
    r"unable to create (new )?native thread",
    r"Cannot allocate memory",
)

_VARIANTS: tuple[tuple[str, str, str], ...] = (
    (
        r"Java heap space",
        "堆内存不足",
        "看三处：① 单次请求是否加载了超大对象（列表不分页、文件整读）"
        "② 是否有缓存 / 集合无上限增长（**最常见的泄漏形态**）"
        "③ 是否配置的 -Xmx 就是不够。用 jmap 抓 dump 比对两次的支配树。",
    ),
    (
        r"Metaspace",
        "元空间不足",
        "动态类太多：CGLIB 代理、反射滥用、热部署残留、Groovy/脚本引擎。"
        "先看 -XX:MaxMetaspaceSize，再查是否有类加载器泄漏。",
    ),
    (
        r"unable to create (new )?native thread",
        "线程数超限",
        "通常是线程泄漏：线程池没关、每次请求 new Thread、"
        "或者线程池队列无界 + 任务堆积。用 jstack 数一下线程名分布。",
    ),
    (
        r"Cannot allocate memory",
        "本机内存不足",
        "JVM 之外的原因：容器内存限额、page cache 挤压、ulimit。"
        "**这种报错 Java 堆调参没用**，要看宿主 / 容器的内存。",
    ),
)

_SOLUTION_TEMPLATE = """\
内存类故障（{variant}）。

{advice}

通用动作（按代价从低到高）：
  1. 立刻缓解：重启 / 扩容（**只治标，但能止血**）
  2. 保留现场：OOM 前加 -XX:+HeapDumpOnOutOfMemoryError
  3. 定位根因：dump 对比分析（找"只涨不跌"的对象）
  4. 修复：给缓存加上限（Caffeine 的 maximumSize）、大查询分页、流式处理替代整读

⚠️ 提醒：**加内存不是方案**。有泄漏的服务，内存加一倍只是把故障时间往后推。"""


class OutOfMemoryAnalyzer(BaseAnalyzer):
    name = "OutOfMemoryAnalyzer"
    description = "内存 / 线程资源耗尽（OOM、Metaspace、无法创建线程）"
    confidence = 0.92

    def match(self, ctx: RuleContext) -> RuleMatch | None:
        hit, pattern = self.any_pattern(_OOM_PATTERNS, ctx.haystack)
        if not hit:
            return None

        variant = "内存耗尽"
        advice = "先确认是哪块内存：堆 / 元空间 / 线程栈 / 本机。错误信息里会写明。"

        for pattern_, name, text in _VARIANTS:
            if self.any_pattern((pattern_,), ctx.haystack)[0]:
                variant, advice = name, text
                break

        hits = [f"命中模式: {pattern}", self.first_line(ctx.haystack)]
        return self.build_match(ctx, _SOLUTION_TEMPLATE.format(variant=variant, advice=advice), hits)