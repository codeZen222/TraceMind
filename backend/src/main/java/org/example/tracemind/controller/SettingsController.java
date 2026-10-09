package org.example.tracemind.controller;

import lombok.RequiredArgsConstructor;
import org.example.tracemind.common.ApiResponse;
import org.example.tracemind.pojo.vo.HealthItemVO;
import org.example.tracemind.pojo.vo.ModelInfoVO;
import org.example.tracemind.service.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统设置接口，契约见说明书 3.3 节**系统**。
 *
 * <p>这是 Step-J1 的验收接口：起服务后 {@code curl http://localhost:8080/api/settings/health}
 * 应该拿到五项探活结果（MySQL / Redis / Elasticsearch / Python / LLM）。
 * 后端依赖没起时不会报错，对应项 {@code ok=false} —— 这才是探活该有的样子。</p>
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping("/health")
    public ApiResponse<List<HealthItemVO>> health() {
        return ApiResponse.ok(settingsService.health());
    }

    @GetMapping("/model")
    public ApiResponse<ModelInfoVO> model() {
        return ApiResponse.ok(settingsService.modelInfo());
    }

    /** 自动诊断开关状态 */
    @GetMapping("/auto-diagnosis")
    public ApiResponse<Map<String, Object>> autoDiagnosis() {
        return ApiResponse.ok(settingsService.autoDiagnosisStatus());
    }

    /** 切换自动诊断开关。body: {"enabled": true/false} */
    @PutMapping("/auto-diagnosis")
    public ApiResponse<Void> setAutoDiagnosis(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        settingsService.setAutoDiagnosis(enabled);
        return ApiResponse.ok();
    }
}
