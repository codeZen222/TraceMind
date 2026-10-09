package org.example.tracemind.common.enums;

/**
 * 历史导入任务状态，契约见说明书 3.3 节
 * {@code GET /api/logs/import/{taskId}/progress} → {@code {total, processed, status}}。
 *
 * <p>列宽只有 VARCHAR(8)，所以取值都很短；前端按 status 决定是继续轮询还是收起进度条。</p>
 */
public enum ImportStatus {

    /** 导入中，前端继续轮询 */
    RUNNING,

    /** 全部完成 */
    DONE,

    /** 失败，前端给出"可重导"入口 */
    FAILED;

    public String code() {
        return name();
    }

    /** 是否已结束，结束就不用再轮询了 */
    public boolean isFinished() {
        return this != RUNNING;
    }

    public static ImportStatus of(String code) {
        if (code == null) {
            return null;
        }
        for (ImportStatus status : values()) {
            if (status.name().equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }
}
