package org.example.tracemind.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.tracemind.common.ApiResponse;
import org.example.tracemind.common.PageResult;
import org.example.tracemind.pojo.dto.ReportGenerateDTO;
import org.example.tracemind.pojo.vo.ReportVO;
import org.example.tracemind.service.ReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /** 生成报告 */
    @PostMapping("/generate")
    public ApiResponse<ReportVO> generate(@RequestBody @Valid ReportGenerateDTO dto) {
        return ApiResponse.ok(reportService.generate(dto));
    }

    /** 列表。projectId 可选 —— 前端"全部项目"时不传 */
    @GetMapping
    public ApiResponse<PageResult<ReportVO>> list(@RequestParam(required = false) String projectId,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(reportService.list(projectId, page, size));
    }

    /** 详情 */
    @GetMapping("/{id}")
    public ApiResponse<ReportVO> detail(@PathVariable String id) {
        return ApiResponse.ok(reportService.getById(id));
    }

    /**
     * 下载 Markdown 文件。
     *
     * <p>★ <b>不套 ApiResponse</b> —— 它返回文件字节流，不是 {code,message,data}。
     * 这是全项目第二个例外（另一个是追问 SSE）。前端拿到的是 Blob。</p>
     *
     * <p>★ <b>但前端实际上不怎么用它</b> —— `downloadReportMarkdown()` 是直接拿
     * 列表/详情里的 `markdown` 字段在前端拼 Blob 的（见 §5.5）。
     * 这个接口仍然要按契约实现，给"以后想直连下载"或 Postman 用。</p>
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        ReportVO report = reportService.getById(id);
        byte[] bytes = report.getMarkdown() == null
                ? new byte[0]
                : report.getMarkdown().getBytes(StandardCharsets.UTF_8);

        // 文件名用报告 id，不用 title —— 中文标题放进 Content-Disposition 会踩编码坑，
        // 浏览器可能把它们显示成乱码或直接截断
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"report-" + id + ".md\"")
                .contentLength(bytes.length)
                .body(bytes);
    }
}