package org.example.tracemind.service.impl;

import org.example.tracemind.collector.CollectorFactory;
import org.example.tracemind.collector.CollectorRegistry;
import org.example.tracemind.collector.CollectorOffsetStore;
import org.example.tracemind.mapper.LogMonitorTaskMapper;
import org.example.tracemind.pojo.entity.LogMonitorTask;
import org.example.tracemind.pojo.vo.MonitorStatusVO;
import org.example.tracemind.service.OwnershipGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 监控状态接口的降级行为。
 *
 * <p>对应已知问题 1：Redis 不可用时 {@code GET /api/projects/{id}/monitor} 原本会抛到
 * 全局兜底 handler、返回 code=50000。现在必须正常返回并如实标 degraded。</p>
 */
@ExtendWith(MockitoExtension.class)
class MonitorServiceImplStatusTest {

    private static final String PROJECT_ID = "p-001";
    private static final String TASK_ID = "t-001";

    @Mock
    private OwnershipGuard ownershipGuard;
    @Mock
    private LogMonitorTaskMapper logMonitorTaskMapper;
    @Mock
    private CollectorRegistry collectorRegistry;
    @Mock
    private CollectorOffsetStore offsetStore;
    /**
     * 采集线程工厂。本类只测 status()，不会真去创建线程，mock 即可 ——
     * 而这正是 R-2.2 的收益证明：改造前 MonitorServiceImpl 持有 10 个协作者、
     * 其中 8 个纯粹是转发给 collector，要测它得把 ES / Python / Redis / ObjectMapper / 雷达全凑齐。
     */
    @Mock
    private CollectorFactory collectorFactory;
    @Mock
    private Executor collectorExecutor;

    @InjectMocks
    private MonitorServiceImpl monitorService;

    private LogMonitorTask task;

    @BeforeEach
    void setUp() {
        task = new LogMonitorTask();
        task.setTaskId(TASK_ID);
        task.setProjectId(PROJECT_ID);
        task.setRunning(true);
        task.setFilePath("C:/logs/app.log");
        task.setFileSize(20480L);
        task.setLinesRead(0L);   // MySQL 快照里是旧值，用来验证 VO 取的是 store 的值
        task.setErrorsDetected(3);
        task.setDegraded(false);
    }

    @Test
    @DisplayName("★ Redis 挂了：status 正常返回（不抛异常），位点取兜底值并标 positionDegraded")
    void statusDegradesInsteadOfThrowingWhenRedisDown() {
        when(ownershipGuard.requireOwned(PROJECT_ID)).thenReturn(null);
        when(logMonitorTaskMapper.selectByProjectId(PROJECT_ID)).thenReturn(task);
        // store 内部已把 Redis 异常吃掉并回落，这里模拟它给出的降级结果
        when(offsetStore.read(TASK_ID)).thenReturn(new CollectorOffsetStore.Position(8123L, 120L, true));

        MonitorStatusVO vo = monitorService.status(PROJECT_ID);

        assertThat(vo.getOffset()).isEqualTo(8123L);
        assertThat(vo.getLinesRead()).isEqualTo(120L);
        // ★ 只标位点降级；AI 那一路是好的，不能被连累
        assertThat(vo.getPositionDegraded()).isTrue();
        assertThat(vo.getAiDegraded()).isFalse();
        assertThat(vo.getRunning()).isTrue();
    }

    @Test
    @DisplayName("★ 两种降级互不干扰：Python 熔断时 aiDegraded=true、positionDegraded=false")
    void reportsAiDegradationSeparatelyFromPositionDegradation() {
        task.setDegraded(true);   // MySQL 快照里采集线程记下的 Python 熔断状态
        when(ownershipGuard.requireOwned(PROJECT_ID)).thenReturn(null);
        when(logMonitorTaskMapper.selectByProjectId(PROJECT_ID)).thenReturn(task);
        when(offsetStore.read(TASK_ID)).thenReturn(new CollectorOffsetStore.Position(16384L, 250L, false));

        MonitorStatusVO vo = monitorService.status(PROJECT_ID);

        assertThat(vo.getAiDegraded()).isTrue();
        assertThat(vo.getPositionDegraded()).isFalse();
    }

    @Test
    @DisplayName("★ 两种故障同时发生时各自为真，前端能分别提示")
    void reportsBothDegradationsIndependentlyWhenBothDown() {
        task.setDegraded(true);
        when(ownershipGuard.requireOwned(PROJECT_ID)).thenReturn(null);
        when(logMonitorTaskMapper.selectByProjectId(PROJECT_ID)).thenReturn(task);
        when(offsetStore.read(TASK_ID)).thenReturn(new CollectorOffsetStore.Position(8123L, 120L, true));

        MonitorStatusVO vo = monitorService.status(PROJECT_ID);

        assertThat(vo.getAiDegraded()).isTrue();
        assertThat(vo.getPositionDegraded()).isTrue();
    }

    @Test
    @DisplayName("Redis 正常：位点取 store 实时值，两个降级标记都不被误置")
    void statusUsesLivePositionWhenRedisHealthy() {
        when(ownershipGuard.requireOwned(PROJECT_ID)).thenReturn(null);
        when(logMonitorTaskMapper.selectByProjectId(PROJECT_ID)).thenReturn(task);
        when(offsetStore.read(TASK_ID)).thenReturn(new CollectorOffsetStore.Position(16384L, 250L, false));

        MonitorStatusVO vo = monitorService.status(PROJECT_ID);

        assertThat(vo.getOffset()).isEqualTo(16384L);
        assertThat(vo.getLinesRead()).isEqualTo(250L);
        assertThat(vo.getAiDegraded()).isFalse();
        assertThat(vo.getPositionDegraded()).isFalse();
    }

    @Test
    @DisplayName("没有任务时返回空壳对象而不是 404 —— 前端靠它区分「未监听」和「接口挂了」")
    void statusReturnsEmptyShellWhenNoTask() {
        when(ownershipGuard.requireOwned(PROJECT_ID)).thenReturn(null);
        when(logMonitorTaskMapper.selectByProjectId(PROJECT_ID)).thenReturn(null);

        MonitorStatusVO vo = monitorService.status(PROJECT_ID);

        assertThat(vo.getRunning()).isFalse();
        assertThat(vo.getAiDegraded()).isFalse();
        assertThat(vo.getPositionDegraded()).isFalse();
    }
}
