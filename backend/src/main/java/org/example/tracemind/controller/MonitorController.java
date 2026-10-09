package org.example.tracemind.controller;

import lombok.RequiredArgsConstructor;
import org.example.tracemind.common.ApiResponse;
import org.example.tracemind.pojo.vo.MonitorStatusVO;
import org.example.tracemind.service.MonitorService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;

    @PostMapping("/start")
    public ApiResponse<MonitorStatusVO> start(@PathVariable String projectId) {
        return ApiResponse.ok(monitorService.start(projectId));
    }

    @PostMapping("/stop")
    public ApiResponse<MonitorStatusVO> stop(@PathVariable String projectId) {
        return ApiResponse.ok(monitorService.stop(projectId));
    }

    /** 没任务时返回 running=false 的默认对象，不是 404 */
    @GetMapping
    public ApiResponse<MonitorStatusVO> status(@PathVariable String projectId) {
        return ApiResponse.ok(monitorService.status(projectId));
    }
}