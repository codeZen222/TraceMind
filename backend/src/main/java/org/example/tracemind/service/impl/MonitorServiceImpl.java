package org.example.tracemind.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.collector.CollectorFactory;
import org.example.tracemind.collector.CollectorRegistry;
import org.example.tracemind.collector.WatchServiceLogCollector;
import org.example.tracemind.common.BizException;
import org.example.tracemind.common.IdGenerator;
import org.example.tracemind.common.Utilx;
import org.example.tracemind.common.enums.ProjectSource;
import org.example.tracemind.collector.CollectorOffsetStore;
import org.example.tracemind.mapper.LogMonitorTaskMapper;
import org.example.tracemind.pojo.entity.LogMonitorTask;
import org.example.tracemind.pojo.entity.Project;
import org.example.tracemind.pojo.vo.MonitorStatusVO;
import org.example.tracemind.service.MonitorService;
import org.example.tracemind.service.OwnershipGuard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorServiceImpl implements MonitorService {

    private final OwnershipGuard ownershipGuard;
    private final LogMonitorTaskMapper logMonitorTaskMapper;
    private final CollectorRegistry collectorRegistry;
    private final CollectorOffsetStore offsetStore;
    /**
     * 采集线程的创建入口。
     *
     * <p>此前本类为了手工传 12 参构造器，持有了一堆<b>自己压根不用</b>的依赖
     * （StringRedisTemplate / EsLogRepository / PythonClient / ObjectMapper / ErrorRadarService…），
     * 纯粹是依赖穿透。收进 {@link CollectorFactory} 后 service 层的依赖面回到实际需要的四个。</p>
     */
    private final CollectorFactory collectorFactory;

    /** 采集线程池，和导入/诊断分开 */
    @Qualifier("collectorExecutor")
    private final Executor collectorExecutor;

    @Override
    public MonitorStatusVO start(String projectId) {
        // 获取项目
        Project project = requireProject(projectId);

        // ① 只有 PATH 模式能监听
        if (!ProjectSource.PATH.name().equals(project.getSource())) {
            throw BizException.badRequest("仅 PATH 模式可监听");
        }

        String logPath = project.getLogPath();
        if (logPath == null || logPath.isBlank()) {
            throw BizException.badRequest("项目未配置 logPath");
        }
        File file = new File(logPath);
        if (!file.exists() || !file.isFile()) {
            throw BizException.badRequest("日志文件不存在: " + logPath);
        }

        // ② 幂等：已有 running 任务直接返回，不重复起线程
        LogMonitorTask existing = logMonitorTaskMapper.selectByProjectId(projectId);
        if (existing != null && Boolean.TRUE.equals(existing.getRunning())
                && collectorRegistry.isRunning(existing.getTaskId())) {
            log.info("项目已有监听任务在跑，直接返回 taskId={}", existing.getTaskId());
            return toVO(existing);
        }

        // ③ 复用旧任务记录（保留位点），没有就新建。
        //   ★ 新建时才把 offset / linesRead 置 0；复用时必须从 store 读回真实值 ——
        //     ES 文档 ID 是「项目:文件:行号」，行号由 linesRead 累加得出。
        //     原来这里只留 offset、linesRead 归零，于是 stop→start 或进程重启后，
        //     新读的行会被算成第 1 行起，静默覆盖 ES 里最早的文档。
        LogMonitorTask task = existing != null ? existing : new LogMonitorTask();
        if (existing == null) {
            task.setTaskId(IdGenerator.task());
            task.setProjectId(projectId);
            task.setFilePath(logPath);
            task.setOffset(0L);
            task.setFileSize(0L);
            task.setLinesRead(0L);
            task.setErrorsDetected(0);
            task.setRuleMatched(0);
            task.setAgentTriggered(0);
        } else {
            CollectorOffsetStore.Position pos = offsetStore.read(task.getTaskId());
            task.setOffset(pos.offset());
            task.setLinesRead(pos.lines());
        }
        task.setRunning(true);
        task.setFilePath(logPath);
        // 保存任务记录
        if (existing == null) {
            logMonitorTaskMapper.insert(task);
        } else {
            logMonitorTaskMapper.updateStatus(task);
        }

        // ④ 起线程 + 登记。工厂负责注入容器 Bean，这里只给 per-task 的三个值；
        //    create() 内部会调 init() 恢复续读位点（构造期不做 I/O）
        WatchServiceLogCollector collector = collectorFactory.create(task.getTaskId(), projectId, logPath);
        try {
            collectorExecutor.execute(collector);        // 先尝试启动
            collectorRegistry.register(task.getTaskId(), collector);  // 启动成功了再登记
        } catch (Exception e) {
            // 启动失败 → 回滚 MySQL 的 running 状态
            task.setRunning(false);
            logMonitorTaskMapper.updateStatus(task);
            throw BizException.serviceUnavailable("采集线程启动失败: " + e.getMessage());
        }

        return toVO(task);
    }

    @Override
    public MonitorStatusVO stop(String projectId) {
        requireProject(projectId);

        LogMonitorTask task = logMonitorTaskMapper.selectByProjectId(projectId);
        if (task == null || !Boolean.TRUE.equals(task.getRunning())) {
            throw BizException.badRequest("该项目没有正在运行的监听任务");
        }

        // 通知线程停。线程退出时自己会 unregister，这里不重复摘
        WatchServiceLogCollector collector = collectorRegistry.get(task.getTaskId());
        if (collector != null) {
            collector.stop();
        } else {
            log.warn("登记表里没有该任务，可能线程已退出 taskId={}", task.getTaskId());
        }

        // ★ 绝不删位点 —— 下次 start 要接着读
        task.setRunning(false);
        CollectorOffsetStore.Position pos = offsetStore.read(task.getTaskId());
        task.setOffset(pos.offset());
        task.setLinesRead(pos.lines());
        logMonitorTaskMapper.updateStatus(task);

        return toVO(task);
    }

    @Override
    public MonitorStatusVO status(String projectId) {
        requireProject(projectId);

        LogMonitorTask task = logMonitorTaskMapper.selectByProjectId(projectId);
        if (task == null) {
            // ★ 没有任务不是错误，返回默认对象，前端靠这个区分"未监听"和"接口挂了"
            return MonitorStatusVO.empty(projectId);
        }
        return toVO(task);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private Project requireProject(String projectId) {
        // ★ 多租户：归属校验
        return ownershipGuard.requireOwned(projectId);
    }

    /**
     * entity → VO。位点（offset / linesRead）以 store 的实时值为准，不用表里那份快照。
     *
     * <p>两种降级分开报，不合并成一个布尔：
     * {@code aiDegraded} 来自 MySQL 快照里采集线程写入的 Python 熔断状态（影响模板提取），
     * {@code positionDegraded} 来自本次 store 读取是否走了兜底（影响进度新鲜度）。
     * 混在一起时用户看到「降级」不知道该去查 AI 服务还是查 Redis。</p>
     */
    private MonitorStatusVO toVO(LogMonitorTask task) {
        CollectorOffsetStore.Position pos = offsetStore.read(task.getTaskId());
        MonitorStatusVO vo = new MonitorStatusVO();
        vo.setTaskId(task.getTaskId());
        vo.setProjectId(task.getProjectId());
        vo.setRunning(Boolean.TRUE.equals(task.getRunning()));
        vo.setFilePath(task.getFilePath());
        vo.setOffset(pos.offset());
        vo.setFileSize(task.getFileSize());
        vo.setLastReadAt(Utilx.toInstant(task.getLastReadAt()));
        vo.setLinesRead(pos.lines());
        vo.setErrorsDetected(task.getErrorsDetected());
        vo.setRuleMatched(task.getRuleMatched());
        vo.setAgentTriggered(task.getAgentTriggered());
        vo.setAiDegraded(Boolean.TRUE.equals(task.getDegraded()));
        vo.setPositionDegraded(pos.degraded());
        return vo;
    }

    // toInstant 已收口到 common/Utilx —— 此前这里是四份逐字节相同的私有副本之一
}