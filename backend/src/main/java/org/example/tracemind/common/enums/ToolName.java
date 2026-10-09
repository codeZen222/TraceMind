package org.example.tracemind.common.enums;

/**
 * LangGraph Agent 可调用的 6 个工具，契约见说明书 3.2 节 DiagnosisEvent.tool。
 *
 * <p>与 {@link EventType} 同样的坑：线上是小写蛇形，Java 常量是全大写，必须显式映射。</p>
 *
 * <p>注意这些工具是 <b>Python 侧实现</b>的（Java 只做事件转发），
 * Java 里保留这份枚举是为了两件事：① 转发/日志时校验工具名，早发现契约漂移；
 * ② 前端按工具名渲染不同的卡片。真正的工具实现在 {@code trace-mind-ai/tools/}。</p>
 */
public enum ToolName {

    /** 直查 ES log_entry，每条截断 200 字符 */
    SEARCH_LOGS("search_logs"),

    /** Chroma 检索 top-3 历史相似案例 */
    SEARCH_SIMILAR_CASES("search_similar_cases"),

    /** 复用阶段 2 的 AnalyzerRegistry 做规则匹配 */
    MATCH_EXCEPTION_RULES("match_exception_rules"),

    /** ES date_histogram + terms 聚合，统计错误频率 */
    COUNT_ERROR_FREQUENCY("count_error_frequency"),

    /** 按 project.sourceRoot 定位源码，读目标行 ±20 行 */
    READ_SOURCE_CODE("read_source_code"),

    /** httpx GET project.healthUrl，5s 超时 */
    CHECK_PROJECT_HEALTH("check_project_health");

    private final String code;

    ToolName(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ToolName of(String code) {
        if (code == null) {
            return null;
        }
        for (ToolName tool : values()) {
            if (tool.code.equalsIgnoreCase(code)) {
                return tool;
            }
        }
        return null;
    }
}
