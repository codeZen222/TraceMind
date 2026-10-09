package org.example.tracemind.pojo.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 诊断事件，契约见说明书 3.2 节 **DiagnosisEvent**——SSE / WebSocket 里 {@code event} 字段的本体。
 *
 * <p><b>为什么 8 种 type 挤在一个类里：</b>它们的差别只在"带哪些字段"，没有行为差异，前端也是按
 * {@code type} 分支渲染同一个对象。拆成 8 个子类会引入多态反序列化配置，收益为零。</p>
 *
 * <p>{@code @JsonInclude(NON_NULL)} 是关键：序列化时把用不上的 null 字段整个丢掉，
 * 出来的 JSON 才和前端 mock 逐字一致（比如 token 事件只有 type + content）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiagnosisEventVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** stage | rule_hit | tool_call | tool_result | token | thinking | result | error */
    private String type;

    // ---------- stage ----------
    /** 阶段号 1 / 2 / 3，stage 事件与 error 事件可能带 */
    private Integer stage;

    /** 阶段名，如「模板提取」 */
    private String name;

    /** running | done */
    private String status;

    /** 该阶段耗时 */
    private Long costMs;

    // ---------- rule_hit ----------
    /** 命中的规则分析器名，如 NullPointerAnalyzer */
    private String analyzer;

    /** 规则给出的经验解法 */
    private String solution;

    // ---------- tool_call / tool_result ----------
    /** search_logs | search_similar_cases | match_exception_rules | count_error_frequency | read_source_code | check_project_health */
    private String tool;

    /** 工具入参，形状由各工具自己决定 */
    private Map<String, Object> args;

    /** 工具返回摘要（已截断） */
    private String summary;

    /** summary 是否被截断 */
    private Boolean truncated;

    // ---------- token / thinking ----------
    /** 流式文本片段，或思考内容 */
    private String content;

    // ---------- result ----------
    private String rootCause;

    private List<String> evidence;

    private String suggestion;

    private Double confidence;

    // ---------- error ----------
    private String message;

    public static DiagnosisEventVO stage(int stage, String name, String status) {
        return DiagnosisEventVO.builder()
                .type("stage").stage(stage).name(name).status(status).build();
    }

    public static DiagnosisEventVO stageDone(int stage, String name, long costMs) {
        return DiagnosisEventVO.builder()
                .type("stage").stage(stage).name(name).status("done").costMs(costMs).build();
    }

    public static DiagnosisEventVO ruleHit(String analyzer, String solution, long costMs) {
        return DiagnosisEventVO.builder()
                .type("rule_hit").analyzer(analyzer).solution(solution).costMs(costMs).build();
    }

    public static DiagnosisEventVO toolCall(String tool, Map<String, Object> args) {
        return DiagnosisEventVO.builder().type("tool_call").tool(tool).args(args).build();
    }

    public static DiagnosisEventVO toolResult(String tool, String summary, boolean truncated, long costMs) {
        return DiagnosisEventVO.builder()
                .type("tool_result").tool(tool).summary(summary).truncated(truncated).costMs(costMs).build();
    }

    public static DiagnosisEventVO token(String content) {
        return DiagnosisEventVO.builder().type("token").content(content).build();
    }

    public static DiagnosisEventVO thinking(String content) {
        return DiagnosisEventVO.builder().type("thinking").content(content).build();
    }

    public static DiagnosisEventVO error(int stage, String message) {
        return DiagnosisEventVO.builder().type("error").stage(stage).message(message).build();
    }
}
