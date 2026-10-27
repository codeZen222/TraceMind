package org.example.tracemind.common;

import java.time.Duration;

/**
 * Redis key 的唯一命名出口。
 *
 * <h3>为什么要收口到这里</h3>
 * 在此之前，key 前缀以字面量散落在 6 个类里，最危险的一处是 {@code ws:ticket:}——
 * 签发端（{@code DiagnosisController}）和校验端（{@code DiagnosisWebSocketHandler}）
 * 各打了一遍同样的字符串，中间没有任何编译期约束。任一处改名就会变成：
 * <pre>
 *   签发写 A key → 握手验 B key → getAndDelete 恒 null
 *               → 所有 WS 连接静默鉴权失败，而 ticket 接口照常返回 200
 * </pre>
 * 这类 bug 从报错上完全看不出根因。现在两端调同一个方法，物理上无法分叉。
 *
 * <p>这与已修复的「采集位点一份健壮一份裸奔」是同一个病：<b>同一语义两份实现，修一处忘一处</b>。
 * {@code CollectorOffsetStore} 治的是位点那条边，本类治的是命名这条边。</p>
 *
 * <h3>父前缀 {@code tracemind:} 的作用</h3>
 * 原先五个前缀互不相通（{@code ws:} / {@code diag:} / {@code collector:} / {@code fingerprint}），
 * 导致无法用一条 pattern 扫出本平台占用的全部 key，排障和清理只能挨个猜；
 * 而 {@code diag:}、{@code ws:} 这类通用词还可能与同机其他应用撞名——两边读写同一个 key，
 * 表现为「诊断事件顺序错乱」，根因却藏在两个毫不相干的代码库里。
 *
 * <p>代价是每个 key 多十几个字节。这里存的是位点、seq、票据，数量级最多几千（每任务/每会话一个），
 * 不是百万级的缓存场景，所以换「永不撞名 + 一眼看出归属」是划算的。</p>
 *
 * <h3>⚠️ 关于失败语义：故意不在这里统一</h3>
 * 每个 key 下面都注明了「Redis 挂了该怎么办」。这些策略<b>各不相同且大多是有意的</b>，
 * 不要顺手抽成统一的降级逻辑：
 * <ul>
 *   <li><b>位点</b>：兜底信息，回落 MySQL 快照并标 degraded，能读到大概位置就够</li>
 *   <li><b>ticket</b>：安全凭据，发不出就该明确失败（50300）。发个假 ticket 会把故障
 *       搬到握手阶段，用户只看到「连不上」，日志里却是一条成功签发的记录</li>
 *   <li><b>seq</b>：顺序性比完整性重要，硬造 seq 会让回放乱序，所以宁可丢事件</li>
 *   <li><b>开关 / 指纹</b>：非关键路径，静默回落或跳过</li>
 * </ul>
 * 换句话说：<b>命名可以集中，降级决策必须留在各调用点</b>。本类的职责到「给出正确的 key 名和 TTL」为止。
 */
public final class RedisKeys {

    /** 本平台命名空间。改这一行等于整体迁移，注意存量 key。 */
    private static final String NS = "tracemind:";

    // ==================================================================
    // 采集（collector）
    // ==================================================================

    /**
     * 采集位点 Hash，field 为 {@link #F_OFFSET} / {@link #F_LINES}。
     *
     * <p>⚠️ 两个 field 必须在同一次写入里推进：分开存会出现「offset 恢复到第 5000 行、
     * lines 归零」的半恢复状态，于是新读的行被算成第 1 行起，静默覆盖 ES 里最早的文档。</p>
     *
     * <p>失败语义：Redis 不可用时回落 MySQL 快照，并标 {@code positionDegraded=true}。</p>
     */
    public static String collectorPosition(String taskId) {
        return NS + "collector:pos:" + taskId;
    }

    /** 位点 Hash 里的字节偏移 field */
    public static final String F_OFFSET = "offset";

    /** 位点 Hash 里的累计行数 field（ES 文档行号的基准） */
    public static final String F_LINES = "lines";

    /**
     * 日志文件身份指纹（创建时间 + 首行哈希），用于判断文件是否被替换。
     *
     * <p>失败语义：辅助信息，读写失败静默跳过即可——丢了最多多读一轮，
     * 绝不该让采集线程因为一个辅助 key 而退出。</p>
     */
    public static String collectorFingerprint(String taskId) {
        return NS + "collector:fpr:" + taskId;
    }

    // ==================================================================
    // 诊断（diag）
    // ==================================================================

    /**
     * 诊断事件序号发号器（{@code INCR}），会话内严格自增。
     *
     * <p>失败语义：<b>该事件不落库也不推送，直接丢弃</b>。顺序性是回放正确性的地基，
     * 没有真实 seq 就不该伪造一个。</p>
     */
    public static String diagnosisSeq(String sessionId) {
        return NS + "diag:seq:" + sessionId;
    }

    /**
     * 自动诊断开关（全局单 key）。
     *
     * <p>失败语义：回落到 {@code application.yml} 的 {@code diagnosis.auto-trigger} 默认值。
     * 无 TTL——它是运行期配置，不是缓存。</p>
     */
    public static final String DIAGNOSIS_AUTO_TRIGGER = NS + "diag:auto-trigger";

    // ==================================================================
    // WebSocket 握手票据（ws）
    // ==================================================================

    /** WS ticket 的有效期。签发端与消费端引用的必须是同一个常量。 */
    public static final Duration WS_TICKET_TTL = Duration.ofSeconds(60);

    /**
     * 一次性 WebSocket 握手票据。
     *
     * <p>★ 这是本类存在的直接原因：签发与校验过去各写一遍字面量。
     * 浏览器原生 WebSocket 不能带 Authorization 头，所以靠 HTTP 先换票、再把票塞进 query；
     * 握手时 {@code getAndDelete} 消费掉，保证截获也只能重放一次。</p>
     *
     * <p>失败语义：<b>明确抛 50300</b>。发不出票就是连不上实时通道，
     * 别让前端拿着假票去握手后被拒——那会把根因搬到错误的地方。</p>
     */
    public static String wsTicket(String ticket) {
        return NS + "ws:ticket:" + ticket;
    }

    private RedisKeys() {
    }
}
