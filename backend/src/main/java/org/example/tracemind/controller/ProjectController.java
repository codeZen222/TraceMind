package org.example.tracemind.controller;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.common.ApiResponse;
import org.example.tracemind.common.PageResult;
import org.example.tracemind.pojo.dto.ProjectForm;
import org.example.tracemind.pojo.dto.ProjectQueryDTO;
import org.example.tracemind.pojo.vo.ProjectVO;
import org.example.tracemind.service.ProjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
@RequestMapping("/api/projects")
public class ProjectController {

    @Autowired
    private ProjectService projectService;
    /*
        查询项目详情
     */
    @GetMapping("/{id}")
    public ApiResponse<ProjectVO> detail(@PathVariable String id) {
        return ApiResponse.ok(projectService.getById(id));
    }
    /**
     * 新增项目
     */
    @PostMapping
    public ApiResponse<ProjectVO> create(@Valid @RequestBody ProjectForm form) {
        return ApiResponse.ok(projectService.create(form));
    }
    /**
     * 更新项目
     */
    @PutMapping("/{id}")
    public ApiResponse<ProjectVO> update(@PathVariable String id, @Valid @RequestBody ProjectForm form) {
        return ApiResponse.ok(projectService.update(id, form));
    }
    /**
     * 删除项目
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        projectService.delete(id);
        return ApiResponse.ok();
    }
    @GetMapping
    public ApiResponse<PageResult<ProjectVO>> list(ProjectQueryDTO query) {
        return ApiResponse.ok(projectService.page(query));
    }
}
