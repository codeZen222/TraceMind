package org.example.tracemind.controller;

import lombok.RequiredArgsConstructor;
import org.example.tracemind.common.ApiResponse;
import org.example.tracemind.pojo.dto.LogSource;
import org.example.tracemind.pojo.vo.ImportProgressVO;
import org.example.tracemind.service.ImportService;
import org.example.tracemind.service.UploadStaging;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ImportController {

    /** 依赖接口而非实现类（此前注入的是 {@code ImportServiceImpl}，编译期绑死、无法 mock） */
    private final ImportService importService;
    private final UploadStaging uploadStaging;

    /**
     * multipart 字段名必须是 files（前端 form.append('files', file, file.name)）。
     *
     * <h3>★ 为什么这里要先「接管」再启动导入</h3>
     * {@link MultipartFile} 不是文件本身，而是 Servlet 容器临时文件的<b>句柄</b>；
     * HTTP 请求一结束，容器就清理那些临时文件。而导入是<b>异步</b>的
     * （{@code startImport} 提交任务后立即返回 taskId），后台线程真正去读的时刻往往已在请求结束之后
     * —— 于是拿着失效句柄读文件，表现为间歇性、不可复现的「文件读取失败」。
     *
     * <p>所以必须在<b>本方法内、请求还活着的时候</b>把数据复制进应用自己的暂存目录，
     * 之后异步线程只拿普通路径。清理责任也随之转到应用侧，见
     * {@code ImportServiceImpl#runImport} 的 finally。</p>
     *
     * <p>⚠️ 代价：暂存会多占一份磁盘（单请求上限 500MB）。选 {@code Files.copy} 而不是
     * {@code transferTo()} 是因为后者对"目标必须不存在且为绝对路径"的要求在各版本行为不一致，
     * 且内存型 multipart 下省不到磁盘却多出一套分支。</p>
     */
    @PostMapping("/projects/{projectId}/logs/import")
    public ApiResponse<Map<String, String>> importLogs(@PathVariable String projectId,
                                                       @RequestParam("files") MultipartFile[] files) {
        // ★ 两步顺序不可颠倒：
        //   1) 趁请求还活着，把上传内容同步接管到应用自己的暂存目录（拿到的是普通文件路径）；
        //   2) 再交给 service —— 它会立刻返回 taskId 并把读取工作丢进线程池异步跑。
        //   若把 MultipartFile 直接传进第 2 步，异步线程读的时候容器早已清理临时文件（本次修的就是这个）。
        //
        // ⚠️ 刻意不在这里 try/catch 清暂存：一旦 startImport 成功提交了异步任务，
        //   文件就归那边用（它在 finally 里 cleanup）。controller 若在"其实已提交"之后误删，
        //   会把一次正常导入变成读不到文件的失败 —— 清理责任只能有一个人扛，就是 service。
        List<LogSource> sources = uploadStaging.stage(projectId, files);
        return ApiResponse.ok(Map.of("taskId", importService.startImport(projectId, sources)));
    }

    /**
     * 获取导入进度。
     *
     * <p>⚠️ taskId 由 controller 先生成是为了让"暂存目录"和"导入任务"用同一个键，
     * 否则接管完到拿到 id 之间会有个无人认领的窗口。</p>
     */
    @GetMapping("/logs/import/{taskId}/progress")
    public ApiResponse<ImportProgressVO> progress(@PathVariable String taskId) {
        return ApiResponse.ok(importService.progress(taskId));
    }

    private String importTaskId() {
        return "imp-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
