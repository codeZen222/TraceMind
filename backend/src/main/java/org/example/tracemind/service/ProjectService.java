package org.example.tracemind.service;

import org.example.tracemind.common.PageResult;
import org.example.tracemind.pojo.dto.ProjectForm;
import org.example.tracemind.pojo.dto.ProjectQueryDTO;
import org.example.tracemind.pojo.vo.ProjectVO;

public interface ProjectService {
    // 根据 id 查询项目详情
    ProjectVO getById(String id);
    // 新增项目
    ProjectVO create(ProjectForm form);
    // 更新项目
    ProjectVO update(String id, ProjectForm form);
    // 删除项目
    void delete(String id);
    // 查询项目列表
    PageResult<ProjectVO> page(ProjectQueryDTO query);
}
