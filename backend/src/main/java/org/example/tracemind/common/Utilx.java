package org.example.tracemind.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 跨层复用的小工具。
 *
 * <p><b>为什么要有这个类。</b>里面每个方法原本都有多份私有副本，且已经分叉过：
 * {@code rootMessage} 两份、其中一份缺异常链防环；{@code toInstant} 四份逐字节相同；
 * {@code isBlank} / {@code emptyIfNull} 散在三个类。这正是本项目反复出事的形状——
 * <b>同一语义两份实现，修一处忘一处</b>。收口到这里，行为只有一份。</p>
 *
 * <p>刻意保持扁平（不拆 TimeUtils / StringUtils）：这些方法各只有几行，
 * 拆开只会让调用方多记几个类名。</p>
 */
public final class Utilx {

    /**
     * cause 链最多走这么深。
     *
     * <p><b>为什么必须有界。</b>{@code while (t.getCause() != null) t = t.getCause();} 这种写法
     * 遇到自引用（{@code e.initCause(e)}）或成环（A→B→A）的异常会<b>无限循环</b>——
     * 而这行代码通常跑在故障上报路径上，于是"如实报告错误"变成"把处理线程永久卡住"。
     * 本项目里 {@code rootMessage} 曾有两份实现：一份防了自引用、一份完全没防，
     * 正是这类"同一语义两份代码、修一处忘一处"的复发案例。</p>
     *
     * <p>为什么选限步数而不是 IdentityHashMap 查环：真实包装链极少超过几层，
     * 20 已经远超实际需要；而查环要额外分配集合对象，在一个纯字符串提取的工具里不划算。
     * 关键是<b>两种都必须有出口</b>——原先那份只挡了 {@code cause.getCause() == cause}，
     * 对 A→B→A 这种两元素环照样转不出来。</p>
     */
    private static final int MAX_CAUSE_DEPTH = 20;

    /**
     * 取异常链最底层的可读原因。
     *
     * <p>用途：外层异常常被包装成「调用失败」这种没有信息量的话，真正的根因在最内层。
     * 本方法顺着 cause 摸到底，返回它的 message；message 为 null 时退回类名，
     * 保证调用方拼日志时不会拿到 "null"。</p>
     *
     * @param e 任意异常，可为 null
     * @return 永不为 null 的描述串
     */
    public static String rootMessage(Throwable e) {
        if (e == null) {
            return "unknown";
        }
        Throwable cur = e;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH && cur.getCause() != null; depth++) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }

    /**
     * 库里 DATETIME → UTC 时刻。
     *
     * <p>⚠️ 这个方法存在的前提是「库内时间统一存 UTC」，由 JDBC URL 的
     * {@code connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true} 保证
     * （两个参数必须成对出现，见 README 常见坑 1）。
     * 它此前有四份逐字节相同的私有副本散在各 Service 里 —— 收口到这里，
     * 将来若调整时区假设只需改一处。</p>
     */
    public static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    /** 库里 DATETIME → ISO-8601 字符串（给前端的简化形式，等价于 {@code toInstant(t).toString()}）。 */
    public static String toIsoUtc(LocalDateTime time) {
        Instant instant = toInstant(time);
        return instant == null ? null : instant.toString();
    }

    /** null 或全空白。前端契约里若干字段不可空，入参校验到处要用。 */
    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** null 转空串。VO 侧适配前端 types 的不可空 string。 */
    public static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private Utilx() {
    }
}
