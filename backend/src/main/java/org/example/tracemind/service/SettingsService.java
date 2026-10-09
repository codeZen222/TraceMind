package org.example.tracemind.service;

import org.example.tracemind.pojo.vo.HealthItemVO;
import org.example.tracemind.pojo.vo.ModelInfoVO;

import java.util.List;

/**
 * 系统设置：依赖探活与模型配置查询，对应说明书 3.3 节**系统**两个接口。
 */
public interface SettingsService {

    /**
     * 五项依赖探活，固定顺序返回：MySQL / Redis / Elasticsearch / Python / LLM。
     *
     * <p>顺序固定是有原因的：前端把数组按 index 渲染成一排指示灯，
     * 顺序一乱灯的位置就会跳。所以不要改成 Map 或按可用性排序。</p>
     */
    List<HealthItemVO> health();

    /** 读配置透传，不查库、不调 Python */
    ModelInfoVO modelInfo();

    /** 自动诊断开关状态（enabled + limit） */
    java.util.Map<String, Object> autoDiagnosisStatus();

    /** 切换自动诊断开关 */
    void setAutoDiagnosis(boolean enabled);
}
