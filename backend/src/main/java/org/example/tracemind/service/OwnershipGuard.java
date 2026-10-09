package org.example.tracemind.service;

import lombok.RequiredArgsConstructor;
import org.example.tracemind.common.BizException;
import org.example.tracemind.mapper.ProjectMapper;
import org.example.tracemind.pojo.entity.Project;
import org.example.tracemind.security.CurrentUser;
import org.springframework.stereotype.Component;

/**
 * 项目归属校验 —— 行级多租户的闸门。
 *
 * <p>所有按 projectId 操作的接口，入口处先调 {@link #requireOwned(String)}，
 * 确认这个项目属于当前登录用户。归属不匹配抛 404（不暴露"存在但无权限"，防枚举）。</p>
 *
 * <p>独立成组件而不是塞进 ProjectService 的理由：ProjectService 会被 MonitorService 依赖，
 * 而 MonitorService 又要做归属校验 —— 塞一起会成循环依赖。Guard 只依赖 ProjectMapper，谁都能注入。</p>
 */
@Component
@RequiredArgsConstructor
public class OwnershipGuard {

    private final ProjectMapper projectMapper;

    public Project requireOwned(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw BizException.badRequest("projectId 不能为空");
        }
        Project project = projectMapper.selectByIdAndOwner(projectId, CurrentUser.id());
        if (project == null) {
            throw BizException.notFound("项目不存在: " + projectId);
        }
        return project;
    }
}
