package org.example.tracemind.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 生成复盘报告的入参，对应 {@code POST /api/reports/generate} 的 JSON body。
 * <p>后端按 sessionIds 读出各会话结论 + ES 统计，拼成 Markdown 落 report 表。</p>
 */
@Data
public class ReportGenerateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "projectId 不能为空")
    private String projectId;

    @NotEmpty(message = "至少选择一个诊断会话")
    private List<String> sessionIds;
}
