package org.example.tracemind.controller;

import lombok.RequiredArgsConstructor;
import org.example.tracemind.common.ApiResponse;
import org.example.tracemind.pojo.vo.ImportProgressVO;
import org.example.tracemind.service.impl.ImportServiceImpl;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ImportController {

    private final ImportServiceImpl importService;

    /** multipart 字段名必须是 files（前端 form.append('files', file, file.name)） */
    @PostMapping("/projects/{projectId}/logs/import")
    public ApiResponse<Map<String, String>> importLogs(@PathVariable String projectId,
                                                       @RequestParam("files") MultipartFile[] files) {
        return ApiResponse.ok(Map.of("taskId", importService.startImport(projectId, files)));
    }
    /**
     * 获取导入进度
     */

    @GetMapping("/logs/import/{taskId}/progress")
    public ApiResponse<ImportProgressVO> progress(@PathVariable String taskId) {
        return ApiResponse.ok(importService.progress(taskId));
    }
}