"""空指针（NPE）分析器。

最常见的 Java 异常。根因分布很有规律：
  · 集合/Map 取值没判空        → 占大头
  · 远程调用返回 null 没校验   → 次之
  · 自动拆箱（Integer → int）  → 最隐蔽

所以规则给出的不是"某一行代码有 NPE"，而是**引导排查方向**。
真正的定位（哪一行、哪个对象）需要 Agent 去读源码 —— 见 read_source_code 工具。
"""

from __future__ import annotations

from trace_mind_ai.domain.rules.base import BaseAnalyzer, RuleContext, RuleMatch

_NPE_PATTERNS: tuple[str, ...] = (
    r"java\.lang\.NullPointerException",
    r"NullPointerException",
    r"Attempt to invoke virtual method .* on a null object reference",
    r"cannot be null",
)

#: 一眼能看出根因的伴生线索（有的话证据更有力）
_HINTS: tuple[tuple[str, str], ...] = (
    (r"\.get\(.*\)\.", "链式 get 调用未判空"),
    (r"autobox|unbox|Integer.*intValue", "自动拆箱空值"),
    (r"\bFeign\b|\bRestTemplate\b|HttpClient", "远程调用返回值未校验"),
    (r"Optional\.", "Optional 使用问题"),
    (r"@Autowired|@Resource", "注入字段为空（Bean 未初始化）"),
)

_SOLUTION = """\
疑似空指针。按这个顺序排查：

1. 看堆栈里「at <你的包名>」的第一行 —— 那就是出问题的代码位置。
2. 该行的对象来源：
   · 从集合 / Map 取值 → 加 Objects.requireNonNull + 明确的业务异常，
     不要让它以 NPE 的形式冒到顶层
   · 从远程调用返回 → 校验返回值，超时和空返回要区分处理
   · 自动拆箱（Integer 赋值给 int）→ 用 Integer 接，或给默认值
3. 检查是否是可空依赖注入（@Autowired(required=false) / @Nullable）

本次命中的线索见 evidence。如需精确定位到行，可以让我读源码。"""


class NullPointerAnalyzer(BaseAnalyzer):
    name = "NullPointerAnalyzer"
    description = "空指针异常（NullPointerException / 空对象调用）"
    confidence = 0.9

    def match(self, ctx: RuleContext) -> RuleMatch | None:
        hit, pattern = self.any_pattern(_NPE_PATTERNS, ctx.haystack)
        if not hit:
            return None

        hits = [f"命中模式: {pattern}", self.first_line(ctx.haystack)]
        for hint_pattern, description in _HINTS:
            if self.any_pattern((hint_pattern,), ctx.haystack)[0]:
                hits.append(f"线索: {description}")

        return self.build_match(ctx, _SOLUTION, hits)