package org.example.tracemind.service;

import org.example.tracemind.common.PageResult;
import org.example.tracemind.pojo.dto.ReportGenerateDTO;
import org.example.tracemind.pojo.vo.ReportVO;

import java.util.List;

/** 报告域业务接口。下载接口没有对应方法 —— Controller 直接调 getById 拿 markdown */
public interface ReportService {

    /** 生成报告。同步执行（不需要异步：没调 LLM，纯聚合，百毫秒级） */
    ReportVO generate(ReportGenerateDTO dto);

    /**
     * 自动报告内部版：不做归属校验（异步线程调用，入口已校验归属）。
     * 供自动诊断闭环用。无有效会话时返回 null。
     */
    ReportVO generateAuto(String projectId, List<String> sessionIds);

    ReportVO getById(String id);

    /** projectId 可为 null（前端"全部项目"） */
    PageResult<ReportVO> list(String projectId, Integer page, Integer size);
}