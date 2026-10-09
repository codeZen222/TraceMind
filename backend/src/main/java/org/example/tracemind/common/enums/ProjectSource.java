package org.example.tracemind.common.enums;

/**
 * 日志来源方式，契约见说明书 3.2 节 Project.source。
 *
 * <p>两种模式的差别不只是存哪个字段，采集链路也不同：</p>
 * <ul>
 *   <li>{@link #PATH}：后端直接按 {@code logPath} 用 WatchService 实时盯文件</li>
 *   <li>{@link #UPLOAD}：日志是前端传上来的，{@code logPath} 存文件夹名、{@code fileCount} 有值，
 *       只走一次性 import，不实时盯</li>
 * </ul>
 * 所以 {@code MonitorService} 启动采集前必须先判 source，UPLOAD 模式没有可盯的路径。
 */
public enum ProjectSource {

    UPLOAD,

    PATH;

    public String code() {
        return name();
    }

    public static ProjectSource of(String code) {
        if (code == null) {
            return null;
        }
        for (ProjectSource source : values()) {
            if (source.name().equalsIgnoreCase(code)) {
                return source;
            }
        }
        return null;
    }
}
