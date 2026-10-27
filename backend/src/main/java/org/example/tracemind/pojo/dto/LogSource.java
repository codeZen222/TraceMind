package org.example.tracemind.pojo.dto;

import java.nio.file.Path;

/**
 * 一个待导入的日志来源：本地文件路径 + 展示信息。
 *
 * <h3>★ 为什么要有这个类型（本次改动的核心）</h3>
 * 此前 {@code ImportService} 的方法签名直接收 Spring 的 {@code MultipartFile[]}，
 * 而 [#L104] 处把它交给了线程池异步处理 —— 但 HTTP 请求在那之前就已经返回了。
 *
 * <p>{@code MultipartFile} 不是文件本身，而是 Servlet 容器临时文件的<b>句柄</b>：
 * 请求结束时容器会清理这些临时文件。于是后台线程真正去 {@code getInputStream()} 时，
 * 句柄指向的东西可能已经不存在 —— 表现为间歇性、不可复现的「文件读取失败」，
 * 且报错信息完全看不出根因。</p>
 *
 * <p>本类型的存在就是把这件事拆开：<b>趁请求还活着，由 controller 把数据接管到我们自己
 * 控制的目录</b>，service 只拿路径。这样 service 层与 Servlet 容器再无关系，
 * 也才能支持"导入服务器本地已有日志"这类非 HTTP 的来源。</p>
 *
 * @param path        已落到暂存目录的真实文件路径，归应用自己管（含清理责任）
 * @param displayName 用户侧看到的文件名（原始文件名），用于 ES 文档 ID 与进度提示。
 *                    ⚠️ 不能用 {@code path.getFileName()}：接管时会加 taskId 前缀防重名，
 *                    那个物理名字对用户没有意义，还会改变 ES 文档 ID 的中间段
 * @param size        字节数，用于估算总行数（进度条分母）
 */
public record LogSource(Path path, String displayName, long size) {

    /**
     * 校验并给出可读的错误描述。放在这里是因为 controller 和 service 都要用，
     * 而两边判据必须一致 —— 又是一处"同一语义两份实现"的高发地。
     */
    public boolean readable() {
        return path != null && displayName != null && !displayName.isBlank()
                && java.nio.file.Files.isReadable(path);
    }
}
