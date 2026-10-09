package org.example.tracemind.service;

import org.example.tracemind.common.PageResult;
import org.example.tracemind.pojo.dto.LogQueryDTO;
import org.example.tracemind.pojo.vo.LogEntryVO;
import org.example.tracemind.pojo.vo.LogStatsVO;

public interface LogQueryService {

    PageResult<LogEntryVO> search(String projectId, LogQueryDTO query);

    LogStatsVO stats(String projectId);

    LogEntryVO getById(String projectId, String logId);
}