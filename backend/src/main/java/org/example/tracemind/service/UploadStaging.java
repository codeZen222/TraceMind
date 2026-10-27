package org.example.tracemind.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.common.BizException;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.pojo.dto.LogSource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 把 HTTP 上传的文件<b>接管</b>到应用自己的暂存目录。
 *
 * <h3>★ 这个类存在的唯一理由</h3>
 * {@link MultipartFile} 不是文件，是 Servlet 容器临时文件的<b>句柄</b>；
 * 请求一结束容器就会清理它。而日志导入是异步任务（{@code importExecutor.execute(...)}），
 * 它读文件的时刻往往在请求结束之后 —— 于是句柄失效，表现为间歇性的「文件读取失败」，
 * 报错信息完全看不出根因。
 *
 * <p>本类的职责就是把这件事的时序钉死：<b>在 controller 里、请求还活着的时候同步完成接管</b>，
 * 之后异步线程只拿普通路径。这样数据生命周期归应用管，与容器无关。</p>
 *
 * <h3>为什么用复制而不是 {@code transferTo()}</h3>
 * {@code MultipartFile#transferTo} 语义上是"移动"、不占双份磁盘，看起来更省。但它有两个坑：
 * <ol>
 *   <li>要求目标文件<b>不存在</b>且必须是绝对路径，否则各版本行为不一致（有的抛异常、有的静默失败）；</li>
 *   <li>底层若是内存型 multipart（小文件不进盘），实现可能退化成写一份而原临时文件仍等容器清 ——
 *       省不到磁盘，却多了一套分支。</li>
 * </ol>
 * 这里选 {@code Files.copy}：行为可预测、失败就是 IOException，代价是多占一份磁盘，
 * 由 {@link #cleanup} 保证用完即删。单请求上限 500MB，可控。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadStaging {

    private final TraceMindProperties properties;

    /**
     * 把一次导入请求的所有文件接管进 {@code <stagingDir>/<groupKey>/}。
     *
     * <p>⚠️ 必须在<b>请求线程内</b>同步调用。一旦返回给异步线程，就来不及了。</p>
     *
     * @param groupKey 子目录名，用于隔离与后续整目录清理。调用方传 projectId 即可
     *                 （同一项目本就禁止并发导入，见 {@code ImportServiceImpl#startImport}）
     * @return 与入参同序的来源列表；某个文件读不了就整体失败（宁可一条都不导，也不要导一半）
     */
    public List<LogSource> stage(String groupKey, MultipartFile[] files) {
        Path dir = stagingDir(groupKey);
        List<LogSource> staged = new ArrayList<>(files.length);
        try {
            Files.createDirectories(dir);
            for (int i = 0; i < files.length; i++) {
                MultipartFile f = files[i];
                String original = f.getOriginalFilename();
                if (original == null || original.isBlank()) {
                    throw BizException.badRequest("第 " + (i + 1) + " 个文件没有文件名，无法导入");
                }
                // ★ 物理落盘名带序号前缀：同名不同目录上传的两个文件（app.log / backup/app.log）
                //   裸名会互相覆盖，第二个直接把第一个的数据抹掉。展示名仍保留原始名。
                Path target = dir.resolve(i + "-" + sanitize(original));
                try (InputStream in = f.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                staged.add(new LogSource(target, original, f.getSize()));
            }
            log.info("已接管 {} 个上传文件 dir={}", staged.size(), dir);
            return staged;
        } catch (BizException be) {
            cleanup(groupKey);      // 半途失败也要清，别把已经拷进来的留下
            throw be;
        } catch (IOException e) {
            cleanup(groupKey);
            log.error("接管上传文件失败 group={}", groupKey, e);
            throw BizException.serviceUnavailable("暂存上传文件失败，请重试");
        }
    }

    /**
     * 删除该组的全部暂存文件。<b>成功和失败路径都必须调用</b>。
     *
     * <p>接管之后这些文件的清理责任就从 Servlet 容器转到了我们手上 —— 忘了删就是磁盘泄漏，
     * 而每次导入最多 500MB，攒几次就把盘占了。所以调用点在 finally 里，不在正常流程末尾。</p>
     */
    public void cleanup(String groupKey) {
        Path dir = stagingDir(groupKey);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            // 先删文件再删目录：逆序保证父目录排在子项之后
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("删除暂存文件失败 path={} 原因={}", p, e.getMessage());
                        }
                    });
            log.debug("已清理暂存目录 dir={}", dir);
        } catch (IOException e) {
            log.warn("遍历暂存目录失败 dir={} 原因={}", dir, e.getMessage());
        }
    }

    private Path stagingDir(String taskId) {
        return Paths.get(properties.getUpload().getStagingDir()).toAbsolutePath().normalize()
                .resolve(sanitize(taskId));
    }

    /**
     * 去掉路径分隔符与上跳序列。
     *
     * <p>{@code getOriginalFilename()} 是<b>客户端提供的字符串</b>：浏览器选文件夹时它可能带着
     * {@code C:\foo\bar.log} 或 {@code sub/dir.log} 这样的相对路径。直接拼进 resolve 会让
     * 写入落到暂存目录之外（路径穿越）。这里统一压成安全片段。</p>
     */
    private String sanitize(String name) {
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replace("..", "_").replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        return base.isBlank() ? "unnamed" : base;
    }
}
