package org.example.tracemind.controller;

import lombok.RequiredArgsConstructor;
import org.example.tracemind.common.ApiResponse;
import org.example.tracemind.pojo.vo.NewErrorVO;
import org.example.tracemind.service.ErrorRadarService;
import org.example.tracemind.service.OwnershipGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 新错误雷达的对外接口。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/errors")
@RequiredArgsConstructor
public class ErrorRadarController {

    private final ErrorRadarService errorRadarService;
    private final OwnershipGuard ownershipGuard;

    /** 未读的新错误列表 */
    @GetMapping("/new")
    public ApiResponse<List<NewErrorVO>> newErrors(@PathVariable String projectId) {
        ownershipGuard.requireOwned(projectId);
        return ApiResponse.ok(errorRadarService.newErrors(projectId));
    }

    /** 标记已读（前端「知道了」） */
    @PostMapping("/{clusterId}/ack")
    public ApiResponse<Void> ack(@PathVariable String projectId, @PathVariable String clusterId) {
        ownershipGuard.requireOwned(projectId);
        errorRadarService.ack(projectId, clusterId);
        return ApiResponse.ok();
    }
}
