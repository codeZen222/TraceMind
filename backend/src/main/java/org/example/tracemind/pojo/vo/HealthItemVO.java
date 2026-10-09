package org.example.tracemind.pojo.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 健康探活单项，契约见说明书 3.3 节系统接口 **{@code GET /api/settings/health}**。
 *
 * <p>响应是数组 {@code [{name, ok, latencyMs}]}，五项固定为
 * MySQL / Redis / Elasticsearch / Python / LLM。</p>
 *
 * <p><b>{@code latencyMs} 即使探测失败也要给值</b>（= 失败前的实际耗时），不能返回 null。
 * 前端 {@code types/common.ts} 定义的是 {@code latencyMs: number}（不可空），
 * 且 {@code WorkbenchView.vue} 里是无条件渲染 {@code {{ h.latencyMs }}ms}——
 * 回 null 会渲染出一个光秃秃的 "ms"。给真实耗时既不违反类型，也更有诊断价值：
 * 连不上时"耗了 2003ms 才超时"和"瞬间被拒绝"是两种完全不同的故障。</p>
 */
@Data
public class HealthItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** MySQL | Redis | Elasticsearch | Python | LLM */
    private String name;

    /** 是否可用 */
    private Boolean ok;

    /** 探活耗时（毫秒）。失败时是"失败前的耗时"，永不为 null */
    private Long latencyMs;

    public static HealthItemVO of(String name, boolean ok, long latencyMs) {
        HealthItemVO vo = new HealthItemVO();
        vo.setName(name);
        vo.setOk(ok);
        vo.setLatencyMs(latencyMs);
        return vo;
    }
}
