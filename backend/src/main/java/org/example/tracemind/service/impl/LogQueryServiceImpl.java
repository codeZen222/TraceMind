package org.example.tracemind.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.tracemind.common.BizException;
import org.example.tracemind.common.PageResult;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.pojo.dto.LogQueryDTO;
import org.example.tracemind.pojo.vo.LogEntryVO;
import org.example.tracemind.pojo.vo.LogStatsVO;
import org.example.tracemind.service.LogQueryService;
import org.example.tracemind.service.OwnershipGuard;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LogQueryServiceImpl implements LogQueryService {

    private final OwnershipGuard ownershipGuard;
    private final EsLogRepository esLogRepository;

    /**
     * 日志检索。
     */
    @Override
    public PageResult<LogEntryVO> search(String projectId, LogQueryDTO query) {
        requireProject(projectId);
        return esLogRepository.search(projectId, query);
    }
    /**
     * 日志统计。
     */

    @Override
    public LogStatsVO stats(String projectId) {
        requireProject(projectId);
        return esLogRepository.aggregate(projectId);
    }
    /**
     * 日志详情。
     */

    @Override
    public LogEntryVO getById(String projectId, String logId) {
        requireProject(projectId);
        LogEntryVO log = esLogRepository.getById(logId);
        if (log == null) {
            throw BizException.notFound("日志不存在: " + logId);
        }
        // ★ 归属校验：ES 里的 log 也带 projectId，防止拿 A 项目的 id 去查 B 项目的日志
        if (!projectId.equals(log.getProjectId())) {
            throw BizException.notFound("日志不存在: " + logId);
        }
        return log;
    }

    /**
     * 校验项目存在且归属当前用户（多租户闸门）。
     */
    private void requireProject(String projectId) {
        ownershipGuard.requireOwned(projectId);
    }
}