package org.example.tracemind.es;

import org.example.tracemind.pojo.entity.LogEntryDoc;

import java.util.List;

/**
 * ES bulk 写入的<b>逐条</b>结果。
 *
 * <h3>为什么需要这个类型</h3>
 * ES 的 bulk API 语义是「HTTP 200 + {@code errors: true} + 每条文档的独立结果」——
 * 也就是说"这批里有几条被拒"在协议层面是一次<b>成功的请求</b>，客户端拿不到异常。
 *
 * <p>原先 {@code bulkInsert} 返回 void、检测到 errors 只打三行日志就 return，
 * 于是调用方把"部分失败"当成"全部成功"，造成两个真实后果：</p>
 * <ul>
 *   <li><b>兜底重放误删文件</b>：{@code FallbackReplayJob} 的逻辑是"不抛就删文件"，
 *       一批里若有 N 条写不进去，第一次重放就把整个文件删掉，那 N 条<b>永久消失且不再重试</b>。</li>
 *   <li><b>导入谎报完成</b>：{@code ImportServiceImpl} 按行数累加进度，失败的行也被计入 processed，
 *       最终状态还是 DONE —— 用户看到「12500/12500 已完成」但实际少了数据。</li>
 * </ul>
 *
 * <p>本类的职责就是把真相交还给调用方。<b>故意不在这里决定怎么处置失败</b>——
 * 采集侧要落盘、重放侧要重写剩余、导入侧要如实报错，三者策略不同，只能各自处理。</p>
 *
 * <h3>⚠️ 与整批失败的分工</h3>
 * 只有「请求发出去了、拿到逐条结果」才返回本类型。若 ES 连不上 / 整批被拒（IOException），
 * {@code bulkInsert} 仍然<b>抛异常</b>。语义区分：
 * <ul>
 *   <li>抛异常 = 一条都没确认 → 调用方按整批重试或整批落盘</li>
 *   <li>返回本类型 = 已发出，逐条结果如下 → 调用方按明细做部分处置</li>
 * </ul>
 * 这样既让"部分失败"可见，又不破坏现有针对整批故障的重试逻辑。
 *
 * @param succeeded 确认写入 ES 的文档
 * @param failed    被 ES 拒绝的文档及原因，需要调用方决定重试／落盘／报错
 */
public record BulkResult(List<LogEntryDoc> succeeded, List<Failed> failed) {

    /**
     * 一条被拒绝的文档。
     *
     * <p>★ 必须携带 {@link LogEntryDoc} 本身而不是只给 id：兜底重放要把原文写回文件继续重试，
     * 而这条文档压根没进 ES，拿着 id 回查只会查到一个空。</p>
     *
     * @param doc    原始文档
     * @param reason ES 给的拒绝原因（用于日志与人工排查）
     */
    public record Failed(LogEntryDoc doc, String reason) {
    }

    /** 是否全部写入成功——只有这个为 true 时调用方才可以安全丢弃本地副本。 */
    public boolean allSucceeded() {
        return failed.isEmpty();
    }

    /** 本次提交涉及的文档总数。 */
    public int total() {
        return succeeded.size() + failed.size();
    }

    /** 成功条数。导入进度计数要用它，不能用提交数。 */
    public int successCount() {
        return succeeded.size();
    }

    /** 取出失败文档列表，便于落盘或重写剩余。 */
    public List<LogEntryDoc> failedDocs() {
        return failed.stream().map(Failed::doc).toList();
    }

    /** 全成功用的快捷构造，避免各处手写 emptyList。 */
    public static BulkResult allOk(List<LogEntryDoc> docs) {
        return new BulkResult(docs, List.of());
    }
}
