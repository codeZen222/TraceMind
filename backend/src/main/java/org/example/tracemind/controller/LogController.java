package org.example.tracemind.controller;

import lombok.RequiredArgsConstructor;
import org.example.tracemind.common.ApiResponse;
import org.example.tracemind.common.PageResult;
import org.example.tracemind.pojo.dto.LogQueryDTO;
import org.example.tracemind.pojo.vo.LogEntryVO;
import org.example.tracemind.pojo.vo.LogStatsVO;
import org.example.tracemind.service.LogQueryService;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogQueryService logQueryService;

    /**
     * 日志检索。
     *
     * <p>⚠️ <b>参数必须用 {@code @RequestParam MultiValueMap} 接，不能用 {@code @ModelAttribute LogQueryDTO}。</b>
     * 前端 axios 把数组序列化成 {@code level[]=ERROR&level[]=WARN}（带方括号），
     * Spring 的 {@code List<String> level} 绑不上这种 key，结果是"用户勾了 ERROR，后端当不过滤"——
     * <b>不报错，只出错数据</b>。{@code LogQueryDTO.fromParams()} 专门兼容两种写法。</p>
     */
    /**
     * 日志检索。
     */
    @GetMapping
    public ApiResponse<PageResult<LogEntryVO>> search(@PathVariable String projectId,
                                                      @RequestParam MultiValueMap<String, String> params) {
        return ApiResponse.ok(logQueryService.search(projectId, LogQueryDTO.fromParams(params)));
    }
    /**
     * 日志统计。
     */
    @GetMapping("/stats")
    public ApiResponse<LogStatsVO> stats(@PathVariable String projectId) {
        return ApiResponse.ok(logQueryService.stats(projectId));
    }
    /**
     * 日志详情。
     */
    @GetMapping("/{logId}")
    public ApiResponse<LogEntryVO> detail(@PathVariable String projectId,
                                          @PathVariable String logId) {
        return ApiResponse.ok(logQueryService.getById(projectId, logId));
    }
}