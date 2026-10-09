package org.example.tracemind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.aiclient.PythonClient;
import org.example.tracemind.collector.CollectorRegistry;
import org.example.tracemind.collector.WatchServiceLogCollector;
import org.example.tracemind.common.BizException;
import org.example.tracemind.common.IdGenerator;
import org.example.tracemind.common.enums.ProjectSource;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.mapper.LogMonitorTaskMapper;
import org.example.tracemind.pojo.entity.LogMonitorTask;
import org.example.tracemind.pojo.entity.Project;
import org.example.tracemind.pojo.vo.MonitorStatusVO;
import org.example.tracemind.service.ErrorRadarService;
import org.example.tracemind.service.MonitorService;
import org.example.tracemind.service.OwnershipGuard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final StringRedisTemplate redis;
    private final EsLogRepository esLogRepository;
    private final PythonClient pythonClient;
    private final TraceMindProperties properties;
    private final ObjectMapper objectMapper;
    private final ErrorRadarService errorRadarService;

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

        // ③ 复用旧任务记录（保留 offset），没有就新建
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
        }
        task.setRunning(true);
        task.setFilePath(logPath);
        // 保存任务记录
        if (existing == null) {
            logMonitorTaskMapper.insert(task);
        } else {
            logMonitorTaskMapper.updateStatus(task);
        }

        // ④ 起线程 + 登记
        WatchServiceLogCollector collector = new WatchServiceLogCollector(
                task.getTaskId(), projectId, logPath,
                redis, esLogRepository, pythonClient, logMonitorTaskMapper, collectorRegistry,
                properties, objectMapper, errorRadarService);
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

        // ★ 绝不删 Redis 里的 offset —— 下次 start 要接着读
        task.setRunning(false);
        task.setOffset(readOffsetFromRedis(task.getTaskId()));
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

    /** Redis 里的 offset 才是真身，MySQL 那份只是快照 */
    private Long readOffsetFromRedis(String taskId) {
        String v = redis.opsForValue().get("offset:" + taskId);
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private MonitorStatusVO toVO(LogMonitorTask task) {
        MonitorStatusVO vo = new MonitorStatusVO();
        vo.setTaskId(task.getTaskId());
        vo.setProjectId(task.getProjectId());
        vo.setRunning(Boolean.TRUE.equals(task.getRunning()));
        vo.setFilePath(task.getFilePath());
        vo.setOffset(readOffsetFromRedis(task.getTaskId()));
        vo.setFileSize(task.getFileSize());
        vo.setLastReadAt(toInstant(task.getLastReadAt()));
        vo.setLinesRead(task.getLinesRead());
        vo.setErrorsDetected(task.getErrorsDetected());
        vo.setRuleMatched(task.getRuleMatched());
        vo.setAgentTriggered(task.getAgentTriggered());
        vo.setDegraded(Boolean.TRUE.equals(task.getDegraded()));
        return vo;
    }

    private Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}