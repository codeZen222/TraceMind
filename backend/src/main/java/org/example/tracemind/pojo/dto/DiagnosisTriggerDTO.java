package org.example.tracemind.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 触发诊断的入参，对应 {@code POST /api/diagnosis/trigger} 的 JSON body。
 * <p>响应是 {@code {sessionId}}；幂等规则：同一 logId 已存在未结束（RUNNING）会话时直接复用，不新建。</p>
 */
@Data
public class DiagnosisTriggerDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "projectId 不能为空")
    private String projectId;

    /** 触发诊断的日志 id，同时作为幂等键 */
    @NotBlank(message = "logId 不能为空")
    private String logId;
}
