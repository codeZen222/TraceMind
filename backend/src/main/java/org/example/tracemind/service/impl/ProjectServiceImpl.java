package org.example.tracemind.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.collector.CollectorOffsetStore;
import org.example.tracemind.common.BizException;
import org.example.tracemind.common.IdGenerator;
import org.example.tracemind.common.PageResult;
import org.example.tracemind.common.Utilx;
import org.example.tracemind.common.enums.ProjectSource;
import org.example.tracemind.common.enums.ProjectStatus;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.mapper.ImportTaskMapper;
import org.example.tracemind.mapper.LogMonitorTaskMapper;
import org.example.tracemind.mapper.ProjectMapper;
import org.example.tracemind.pojo.dto.ProjectForm;
import org.example.tracemind.pojo.dto.ProjectQueryDTO;
import org.example.tracemind.pojo.entity.LogMonitorTask;
import org.example.tracemind.pojo.entity.Project;
import org.example.tracemind.pojo.vo.ProjectVO;
import org.example.tracemind.security.CurrentUser;
import org.example.tracemind.service.MonitorService;
import org.example.tracemind.service.ProjectService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j

public class ProjectServiceImpl implements ProjectService {
    @Autowired
    private ProjectMapper projectMapper;
    /**
     * 根据 id 查询项目详情
     */
    @Override
    public ProjectVO getById(String id) {
        // ★ 多租户：只查归属当前用户的项目。归属不匹配返回 null → 当"不存在"处理，
        //   不暴露"存在但你不拥有它"（避免枚举别人项目 id）
        Project project = projectMapper.selectByIdAndOwner(id, CurrentUser.id());
        if (project == null) {
            throw BizException.notFound("项目不存在: " + id);
        }
        ProjectVO vo = toVO(project);
        vo.setErrorCount24h((int) esLogRepository.countError24h(id));
        vo.setDiagnosisCount(project.getFileCount());
        return vo;
    }

    /** entity → VO。只在这个类里用，所以写成私有方法就够 */
    private ProjectVO toVO(Project entity) {
        ProjectVO vo = new ProjectVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setDescription(emptyIfNull(entity.getDescription()));
        vo.setStatus(entity.getStatus());
        vo.setLanguage(emptyIfNull(entity.getLanguage()));
        vo.setSource(entity.getSource());
        vo.setLogPath(emptyIfNull(entity.getLogPath()));
        vo.setFileCount(entity.getFileCount());
        vo.setHealthUrl(entity.getHealthUrl());
        vo.setSourceRoot(entity.getSourceRoot());
        vo.setCreatedAt(Utilx.toInstant(entity.getCreatedAt()));
        vo.setUpdatedAt(Utilx.toInstant(entity.getUpdatedAt()));
        // TODO J2 阶段派生统计先给 0，J3 接 ES、J6 接 agent_session 时再回来改
        vo.setErrorCount24h(0);
        vo.setDiagnosisCount(0);
        return vo;
    }

    /** null 转空串，委托 Utilx（本类多处使用，留一层短名便于阅读） */
    private String emptyIfNull(String value) {
        return Utilx.emptyIfNull(value);
    }

    /**
     * 新增项目
     */
    @Override
    public ProjectVO create(ProjectForm form) {
        validateSource(form);

        String source = form.getSource() == null ? ProjectSource.PATH.name() : form.getSource();

        Project entity = new Project();
        entity.setId(IdGenerator.project());
        entity.setName(form.getName());
        entity.setDescription(form.getDescription());
        entity.setStatus(form.getStatus() == null ? ProjectStatus.DEVELOPING.name() : form.getStatus());
        entity.setLanguage(form.getLanguage());
        entity.setSource(source);
        // UPLOAD 模式下 logPath 存的是文件夹名
        entity.setLogPath(ProjectSource.UPLOAD.name().equals(source) ? form.getFolderName() : form.getLogPath());
        entity.setHealthUrl(form.getHealthUrl());
        entity.setSourceRoot(form.getSourceRoot());
        // 多租户：新建项目归属当前登录用户
        entity.setOwnerId(CurrentUser.id());
        if (ProjectSource.UPLOAD.name().equals(source) && form.getFiles() != null) {
            entity.setFileCount(form.getFiles().size());
        }
        // createdAt / updatedAt 不设，交给数据库默认值

        projectMapper.insert(entity);

        // ★ 必须再查一次：MySQL 的 DEFAULT CURRENT_TIMESTAMP 不会回填到 Java 对象
        return toVO(projectMapper.selectById(entity.getId()));
    }

    /** Bean Validation 表达不了"字段 A 的值决定字段 B 是否必填"，只能写在 Service */
    private void validateSource(ProjectForm form) {
        String source = form.getSource() == null ? ProjectSource.PATH.name() : form.getSource();
        if (ProjectSource.PATH.name().equals(source) && isBlank(form.getLogPath())) {
            throw BizException.badRequest("PATH 模式必须填 logPath");
        }
        if (ProjectSource.UPLOAD.name().equals(source) && isBlank(form.getFolderName())) {
            throw BizException.badRequest("UPLOAD 模式必须填 folderName");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
    /**
     * 更新项目
     */
    @Override
    public ProjectVO update(String id, ProjectForm form) {
        Project project = projectMapper.selectByIdAndOwner(id, CurrentUser.id());
        if (project == null) {
            throw BizException.notFound("项目不存在: " + id);
        }

        // 只覆盖"前端真的传了"的字段，null 一律跳过
        if (form.getName() != null) project.setName(form.getName());
        if (form.getDescription() != null) project.setDescription(form.getDescription());
        if (form.getStatus() != null) project.setStatus(form.getStatus());
        if (form.getLanguage() != null) project.setLanguage(form.getLanguage());
        if (form.getHealthUrl() != null) project.setHealthUrl(form.getHealthUrl());
        if (form.getSourceRoot() != null) project.setSourceRoot(form.getSourceRoot());

        // source / logPath 一起处理，并复用 create 的交叉校验
        if (form.getSource() != null) {
            validateSource(form);
            project.setSource(form.getSource());
            project.setLogPath(ProjectSource.UPLOAD.name().equals(form.getSource())
                    ? form.getFolderName() : form.getLogPath());
        }

        projectMapper.update(project);
        return toVO(projectMapper.selectById(id));   // ★ 改完再查，拿 DB 的 updated_at
    }
    /**
     * 删除项目
     */
    @Autowired
    private LogMonitorTaskMapper logMonitorTaskMapper;
    @Autowired
    private MonitorService monitorService;
    @Autowired
    private ImportTaskMapper importTaskMapper;
    @Autowired
    private CollectorOffsetStore offsetStore;
    @Override
    public void delete(String id) {
        Project project = projectMapper.selectByIdAndOwner(id, CurrentUser.id());
        if (project == null) {
            throw BizException.notFound("项目不存在: " + id);
        }
        // 若有 running 监听任务，先停掉，再删 log_monitor_task 里该项目的记录
        //检查有没有 running 的监听任务
        log.info("检查有没有 running 的监听任务");
        LogMonitorTask monitorTask = logMonitorTaskMapper.selectByProjectId(id);
        if (monitorTask != null && monitorTask.getRunning()) {
            log.info("有 running 的监听任务，先停掉");
            monitorService.stop(id);
        }
        // 2) 清关联子表
        logMonitorTaskMapper.deleteByProjectId(id);
        importTaskMapper.deleteByProjectId(id);

        // 3) ★ 回收 Redis 位点。位点 key 没有 TTL，这里是唯一的清理入口 ——
        //    漏掉就会随项目删除无限累积孤儿 key（collector:pos:* / collector:fpr:*）
        if (monitorTask != null) {
            offsetStore.clear(monitorTask.getTaskId());
        }

        // TODO(J6)：agent_session / report 的清理策略待定
        projectMapper.deleteByIdAndOwner(id, CurrentUser.id());
        // ⚠️ 绝不级联删 ES 日志 / agent_session / report —— 复盘数据比项目本身值钱
    }

    /**
     * 分页查询项目列表
     */

    @Autowired
    private EsLogRepository esLogRepository;
    @Override
    public PageResult<ProjectVO> page(ProjectQueryDTO query) {
        int page = query.pageOrDefault();
        int size = query.sizeOrDefault();

        // ★ 必须用 statusOrNull()，不能用 getStatus()
        //   前端的"全部"选项传的是字符串 "ALL"，不是枚举值。直接拼进 SQL 会恒为空集，
        //   而且不报错——用户看到"项目全没了"却查不出原因，是最难查的那类 bug
        String status = query.statusOrNull();
        String keyword = (query.getKeyword() == null || query.getKeyword().isBlank())
                ? null : query.getKeyword().trim();

        // ★ 多租户：项目列表只查当前用户自己的项目
        String ownerId = CurrentUser.id();
        long total = projectMapper.countByCondition(ownerId, status, keyword);
        if (total == 0) {
            return PageResult.empty(page, size);
        }

        // ★ 一次查出所有项目，再查每个项目的 24h ERROR 数
        List<Project> rows = projectMapper.selectPage(ownerId, status, keyword, query.offset(), size);
        // 转成 VO 列表
        List<ProjectVO> list = new ArrayList<>(rows.size());
        // 一次查出所有项目的 24h ERROR 数
        List<String> projectIds = rows.stream().map(Project::getId).toList();
        Map<String, Long> errorMap = esLogRepository.batchCountError24h(projectIds);

        for (Project row : rows) {
            ProjectVO vo = toVO(row);
            vo.setErrorCount24h(errorMap.getOrDefault(row.getId(), 0L).intValue());

            vo.setDiagnosisCount(row.getFileCount());
            list.add(vo);
        }
        return PageResult.of(list, total, page, size);
    }
}