package org.example.tracemind.service;

import org.example.tracemind.common.BizException;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.pojo.dto.LogSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上传接管 —— R-4.2 的核心修复。
 *
 * <p><b>要防的回归。</b>{@code MultipartFile} 是 Servlet 容器临时文件的<b>句柄</b>，
 * 请求一结束容器就清理。而导入是异步任务（提交后立即返回 taskId），后台线程真正读文件时
 * 往往已在请求结束之后 —— 于是间歇性地「文件读取失败」，且报错看不出根因。</p>
 *
 * <p>修复是把数据在请求线程内复制进应用自己的目录。本测试锁两件事：
 * ① 产出的 {@link LogSource} 指向真实存在、可独立读取的文件；
 * ② 清理责任明确（不重不漏）。</p>
 */
class UploadStagingTest {

    @TempDir
    Path root;

    private TraceMindProperties properties;
    private UploadStaging staging;

    @BeforeEach
    void setUp() {
        properties = new TraceMindProperties();
        properties.getUpload().setStagingDir(root.toString());
        staging = new UploadStaging(properties);
    }

    /**
     * 模拟一次 multipart 上传（MockMultipartFile 在内存里，不依赖真实 HTTP 请求）。
     *
     * <p>入参是<b>交替排列</b>的「文件名, 内容」序列：{@code uploads("a.log", "body")} 表示一个文件。</p>
     */
    private org.springframework.web.multipart.MultipartFile[] uploads(String... nameAndBody) {
        assertThat(nameAndBody.length % 2)
                .as("参数必须是「文件名, 内容」成对出现")
                .isZero();
        var list = new java.util.ArrayList<org.springframework.web.multipart.MultipartFile>();
        for (int i = 0; i < nameAndBody.length; i += 2) {
            list.add(new MockMultipartFile("files", nameAndBody[i],
                    "text/plain", nameAndBody[i + 1].getBytes(StandardCharsets.UTF_8)));
        }
        return list.toArray(new org.springframework.web.multipart.MultipartFile[0]);
    }

    @Test
    @DisplayName("★ 接管后拿到的是普通文件：脱离任何请求上下文也能读到完整内容")
    void stagedFilesReadableWithoutRequestContext() {
        var files = uploads("app.log", "line one\nline two\nline three");

        List<LogSource> sources = staging.stage("p-001", files);

        assertThat(sources).hasSize(1);
        LogSource s = sources.get(0);
        assertThat(s.readable()).isTrue();
        assertThat(s.size()).isGreaterThan(0);

        // ★ 关键断言：像异步线程那样独立打开这个路径读一遍
        try {
            List<String> lines = Files.readAllLines(s.path(), StandardCharsets.UTF_8);
            assertThat(lines).containsExactly("line one", "line two", "line three");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("displayName 保留用户原始名，物理名另加序号前缀（两者刻意不同）")
    void keepsDisplayNameSeparateFromPhysicalName() {
        List<LogSource> sources = staging.stage("p-001", uploads("app.log", "x"));

        assertThat(sources.get(0).displayName()).isEqualTo("app.log");
        // 物理名带 "0-" 前缀，所以 ES 文档 ID 必须用 displayName —— 见 LogSource 的注释
        assertThat(sources.get(0).path().getFileName().toString()).isEqualTo("0-app.log");
    }

    @Test
    @DisplayName("★ 同名不同目录的两个文件不会互相覆盖（序号前缀的意义）")
    void sameBaseNameFilesDoNotCollide() {
        var files = uploads("app.log", "from root", "app.log", "from backup dir");

        List<LogSource> sources = staging.stage("p-001", files);

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).path()).isNotEqualTo(sources.get(1).path());
        try {
            assertThat(Files.readString(sources.get(0).path())).isEqualTo("from root");
            assertThat(Files.readString(sources.get(1).path())).isEqualTo("from backup dir");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("★ 客户端提供的文件名带路径时不得逃出暂存目录（路径穿越防护）")
    void sanitizesClientSuppliedPathTraversal() {
        // 浏览器选文件夹时 originalFilename 可能是 "../../etc/passwd" 这种形态
        var evil = new MockMultipartFile("files", "../../../../windows/system32/config/sam",
                "text/plain", "boom".getBytes(StandardCharsets.UTF_8));

        List<LogSource> sources = staging.stage("p-001", new org.springframework.web.multipart.MultipartFile[]{evil});

        Path landed = sources.get(0).path();
        assertThat(landed.normalize().startsWith(root.toAbsolutePath().normalize())
                || landed.normalize().startsWith(root.normalize()))
                .as("落点必须在暂存目录内，实际=%s", landed)
                .isTrue();
        assertThat(landed.getFileName().toString()).doesNotContain("..");
    }

    @Test
    @DisplayName("cleanup 删掉整个分组目录，不留孤儿文件占盘")
    void cleanupRemovesWholeGroupDir() {
        List<LogSource> sources = staging.stage("p-001", uploads("a.log", "1\n2"));
        assertThat(sources.get(0).path()).exists();

        staging.cleanup("p-001");

        assertThat(sources.get(0).path()).doesNotExist();
    }

    @Test
    @DisplayName("cleanup 对不存在的目录幂等，不抛 —— 它会被放进 finally")
    void cleanupIsIdempotentOnMissingDir() {
        staging.cleanup("never-used");   // 不应抛出
    }

    @Test
    @DisplayName("空文件名 → 拒绝并清掉已拷进来的部分，不留半成品")
    void rejectsUnnamedFileAndCleansUp() {
        var named = new MockMultipartFile("files", "ok.log", "text/plain", "data".getBytes(StandardCharsets.UTF_8));
        var unnamed = new MockMultipartFile("files", "", "text/plain", "data".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> staging.stage("p-001",
                new org.springframework.web.multipart.MultipartFile[]{named, unnamed}))
                .isInstanceOf(BizException.class);

        // 半途失败也要清干净：第一个文件已经拷进去了
        try (var s = Files.walk(root)) {
            assertThat(s.filter(Files::isRegularFile).count())
                    .as("接管中途失败后不应留下任何已拷贝的文件")
                    .isZero();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
