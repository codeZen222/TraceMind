package org.example.tracemind.pojo.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 错误基线表 error_baseline 实体 —— 新错误雷达的「已见指纹」。
 *
 * <p>一行 = 某项目历史上出现过的<b>一种</b>错误模板（clusterId）。
 * {@code (project_id, cluster_id)} 唯一，「判新」就是往这张表做一次存在性检查：
 * 查不到 → 新错误；查得到 → 旧错误。</p>
 *
 * <p>{@code first_seen_at} 是首次出现时间，后续 git blame 联动要拿它去
 * 交叉「错误首现时间 ∩ 提交时间」来定位是哪次提交引入的。</p>
 */
@Data
public class ErrorBaseline implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String projectId;

    /** Drain3 聚类 id，即错误指纹 */
    private String clusterId;

    /** 首次出现时间 */
    private LocalDateTime firstSeenAt;

    /** 最近一次出现时间 */
    private LocalDateTime lastSeenAt;

    /** 是否已读（前端「知道了」后置 1，不再高亮） */
    private Boolean acknowledged;
}
