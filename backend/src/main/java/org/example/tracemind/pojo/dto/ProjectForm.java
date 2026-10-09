package org.example.tracemind.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 新建 / 修改项目的入参，对应 {@code POST /api/projects}、{@code PUT /api/projects/{id}} 的 JSON body。
 *
 * <p>与实体 {@code Project} 的区别：这里没有 id / createdAt / updatedAt / fileCount（由后端生成），
 * 只收用户真正能填的那几项。</p>
 */
@Data
public class ProjectForm implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "项目名不能为空")
    @Size(max = 64, message = "项目名最长 64 字符")
    private String name;

    @Size(max = 255, message = "描述最长 255 字符")
    private String description;

    /** DEVELOPING | DEPLOYED，不传由数据库默认 DEVELOPING */
    @Pattern(regexp = "DEVELOPING|DEPLOYED", message = "status 只能是 DEVELOPING 或 DEPLOYED")
    private String status;

    /** 技术栈描述，如 Java / Spring Boot */
    @Size(max = 32)
    private String language;

    /** UPLOAD | PATH，不传由数据库默认 PATH */
    @Pattern(regexp = "UPLOAD|PATH", message = "source 只能是 UPLOAD 或 PATH")
    private String source;

    /** source=PATH 时是日志文件绝对路径；source=UPLOAD 时是上传文件夹名 */
    @Size(max = 512)
    private String logPath;

    @Size(max = 512)
    private String healthUrl;

    /** 源码根目录，Python 的 read_source_code 工具用 */
    @Size(max = 512)
    private String sourceRoot;

    /** 仅 UPLOAD 模式：上传文件夹名（前端展示用，后端原样存 logPath） */
    @Size(max = 255)
    private String folderName;

    /**
     * 仅 UPLOAD 模式：前端选中的文件名列表。
     * <p>契约里明确「后端可忽略 files（前端仅展示用），日志导入走 import 接口」，所以这里只做长度校验。</p>
     */
    private List<String> files;
}
