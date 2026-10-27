package org.example.tracemind.service;

import org.example.tracemind.pojo.dto.LogSource;
import org.example.tracemind.pojo.vo.ImportProgressVO;

import java.util.List;

/**
 * 日志导入。
 *
 * <p>★ 入参是 {@link LogSource}（普通文件路径）而不是 {@code MultipartFile} ——
 * 这是刻意的边界：本服务的实现会把任务交给线程池异步执行，而 HTTP 请求在返回 taskId 时就结束了；
 * {@code MultipartFile} 只是容器临时文件的句柄，请求一结束就被清理。
 * 把 Web 类型挡在接口之外，才不会出现"异步线程拿着失效句柄读文件"这个 bug。</p>
 *
 * <p>此前没有这个接口，{@code ImportController} 因此只能注入实现类
 * （编译期绑死、无法用 mock 替换）。它是全项目唯一一个没有接口的 service 实现。</p>
 */
public interface ImportService {

    /**
     * 启动一次异步导入，立即返回任务 id。
     *
     * @param projectId 目标项目（内部会校验归属当前用户）
     * @param sources   已由 controller 接管落盘的日志来源
     * @return 任务 id，形如 {@code imp-8k3m2x9q}
     */
    String startImport(String projectId, List<LogSource> sources);

    /** 查询进度。⚠️ total 与 processed 的单位都是<b>行数</b>，不是批次数。 */
    ImportProgressVO progress(String taskId);
}