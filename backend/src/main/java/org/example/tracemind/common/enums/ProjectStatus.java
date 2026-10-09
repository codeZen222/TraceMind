package org.example.tracemind.common.enums;

/**
 * 项目状态，契约见说明书 3.2 节 Project.status。
 * <p>取值与数据库 {@code project.status} 列一一对应，必须保持全大写原样。</p>
 */
public enum ProjectStatus {

    /** 开发中 */
    DEVELOPING,

    /** 已部署 */
    DEPLOYED;

    /** 与线上取值同名，靠 name() 即可；这里保留一个显式的取值方法，避免各处再出现字面量 */
    public String code() {
        return name();
    }

    public static ProjectStatus of(String code) {
        if (code == null) {
            return null;
        }
        for (ProjectStatus status : values()) {
            if (status.name().equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }
}
