"""SQL 异常分析器。

数据库错误有个特点：**错误码已经说明了原因**，规则的价值是
把错误码翻译成"该动哪里"，并区分「改代码」和「改库」两类动作。
"""

from __future__ import annotations

from trace_mind_ai.domain.rules.base import BaseAnalyzer, RuleContext, RuleMatch

_SQL_PATTERNS: tuple[str, ...] = (
    r"SQLSyntaxErrorException|SQLException|DataAccessException|BadSqlGrammarException",
    r"Duplicate entry",
    r"Data too long for column",
    r"cannot be null|Column '.*' cannot be null",
    r"Deadlock found|Lock wait timeout",
    r"Unknown column|Table '.*' doesn't exist",
)

_CASES: tuple[tuple[str, str], ...] = (
    (
        r"Duplicate entry",
        "**唯一键冲突**。两种含义完全不同："
        "① 业务上真的重复提交 → 前端防重 / 后端幂等（唯一索引是最后一道防线，别删）"
        "② 幂等设计失效 → 检查是否用了「先查再插」（并发下必然失效），"
        "改成「插入 + 捕获冲突」（依赖数据库的唯一约束）",
    ),
    (
        r"Deadlock found|Lock wait timeout",
        "**死锁 / 锁等待超时**。典型成因：两个事务以**相反顺序**更新多行数据。"
        "解法：① 让所有事务按同一顺序（比如按主键升序）访问数据 "
        "② 缩小事务范围（别在事务里做远程调用 —— 那会把锁持有时间放大几百倍）"
        "③ 降低隔离级别（读已提交能减少间隙锁）。",
    ),
    (
        r"Unknown column|Table '.*' doesn't exist",
        "**表结构不一致**。这是**环境问题不是代码问题**："
        "开发库改过 DDL 但没同步到测试 / 生产。"
        "⚠️ 本项目就有一个实例：真库的 log_monitor_task 有 degraded 列"
        "（手动 ALTER 加的），但 resources/db/schema.sql 里没有 ——"
        "**别人照 schema 建库会缺列**。解法是把 DDL 纳入版本管理（Flyway / Liquibase），"
        "禁止手工改库。",
    ),
    (
        r"Data too long for column",
        "**字段长度不够**。看是哪个列："
        "① 业务上确实要更长的 → 改 DDL（同时改 schema 文件）"
        "② 数据异常超长 → 大概率是上游传了脏数据，在入库前做长度校验并拒绝，"
        "**不要靠截断静默吞掉**（截断后的数据更难查）。",
    ),
    (
        r"cannot be null",
        "**非空约束被违反**。排查顺序："
        "① 是 INSERT 还是 UPDATE（UPDATE 漏字段很常见）"
        "② 实体默认值是否生效 —— ⚠️ **显式传 NULL 时数据库的 DEFAULT 不生效**，"
        "默认值要写在实体字段上（Java 侧 `private Boolean degraded = false;`）"
        "③ 是否是 @Param 用错导致整字段为 null"
        "（本项目踩过：单 POJO 参数加 @Param 会被包成 ParamMap，XML 写 #{id} 全字段 NULL）",
    ),
)

_SOLUTION_TEMPLATE = """\
数据库操作异常。

{case}

通用检查：
  · 看完整异常的 `Caused by` —— 表层异常（DataAccessException）通常只是包装，
    真正的原因在最后一层
  · 确认执行到了哪个环境（开发改过、生产没改，是最常见的"只有生产报错"）"""


class SqlErrorAnalyzer(BaseAnalyzer):
    name = "SqlErrorAnalyzer"
    description = "SQL 执行异常（语法 / 约束 / 死锁 / 表结构不一致）"
    confidence = 0.87

    def match(self, ctx: RuleContext) -> RuleMatch | None:
        hit, pattern = self.any_pattern(_SQL_PATTERNS, ctx.haystack)
        if not hit:
            return None

        case = "先看 `Caused by` 里的数据库原生错误码，再对号入座。"
        for pattern_, text in _CASES:
            if self.any_pattern((pattern_,), ctx.haystack)[0]:
                case = text
                break

        hits = [f"命中模式: {pattern}", self.first_line(ctx.haystack)]
        return self.build_match(ctx, _SOLUTION_TEMPLATE.format(case=case), hits)