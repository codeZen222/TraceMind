package org.example.tracemind.es;

import org.example.tracemind.pojo.entity.LogEntryDoc;

/**
 * ES 日志文档 ID 的<b>唯一</b>生成与解析出口。
 *
 * <h3>为什么必须收口</h3>
 * 此前同一个 ID 有三份独立实现，各自演化：
 * <ul>
 *   <li>采集链路拼 {@code projectId:绝对路径:行号}</li>
 *   <li>导入链路拼 {@code projectId:裸文件名:行号}</li>
 *   <li>报告链路再用 {@code indexOf(':')} 手工把它拆回来</li>
 * </ul>
 * README 常见坑里记录的「同一行给出 {@code :509} 和 {@code :510}」事故就是这个结构造成的——
 * 修复只落在了一侧，另一份手写的 {@code +1} 至今仍在。<b>三份实现没有一份是 single source of truth。</b>
 *
 * <h3>★ 现在的规则（本次统一）</h3>
 * <pre>
 *   {projectId}:{fileName}:{lineNumber}
 * </pre>
 * 中间段一律用<b>裸文件名</b>（{@code app.log}），两条链路一致。选它而不是绝对路径的原因：
 * <ol>
 *   <li><b>导入链路拿不到绝对路径</b>。multipart 上传只有 {@code getOriginalFilename()}，
 *       用 folderName 去拼一个"近似全路径"本质上是伪造，不如诚实用文件名。</li>
 *   <li><b>避开分隔符冲突</b>。Windows 盘符自带冒号（{@code C:/logs/app.log}），
 *       放进以冒号为分隔符的 ID 里会让 {@code split(":")} 截断在 {@code C}。
 *       文件名不含冒号，从根上消除这个脆弱性。</li>
 *   <li>更短、人类可读，前端展示 {@code app.log:510} 直接就能去翻文件。</li>
 * </ol>
 *
 * <p>⚠️ <b>已知取舍</b>：同一项目同时监听两个不同目录下的同名文件时，两者会算出相同 ID 并互相覆盖。
 * 当前一个项目只对应一个 {@code logPath}（{@code log_monitor_task} 按 projectId 唯一），
 * 所以这种组合不会出现。若将来支持多文件监听，需要把区分度加回中间段——届时只改本类即可。</p>
 *
 * <p>⚠️ 历史数据不迁移：改造前写入的采集文档 ID 中间段是绝对路径，与新格式不同形。
 * 二者不会互相覆盖（字符串本就不同），代价只是同一物理行可能存在两份索引数据导致统计重复计数。
 * 本项目当前无存量数据，故未做 reindex。</p>
 */
public final class EsDocId {

    private static final char SEP = ':';

    /**
     * 生成文档 ID。
     *
     * @param projectId  项目 id，形如 {@code p-8k3m2x9q}（由 {@code IdGenerator} 产出，不含冒号）
     * @param fileName   <b>裸文件名</b>，不是路径。传绝对路径会被自动截取末段，避免调用方误传
     * @param lineNumber 1 基行号（与 IDEA 侧边栏、{@code grep -n} 对齐）
     */
    public static String of(String projectId, String fileName, int lineNumber) {
        return projectId + SEP + bareFileName(fileName) + SEP + lineNumber;
    }

    /**
     * 取文件名的末段。传入已是裸名时原样返回。
     *
     * <p>之所以在这里兜一层而不是信任调用方：采集链路手里握着的是绝对路径
     * （{@code WatchServiceLogCollector.filePath}），一旦哪天有人图省事直接传进来，
     * Windows 路径里的冒号就会污染 ID 的分隔符结构。宁可在此归一化。</p>
     */
    public static String bareFileName(String pathOrName) {
        if (pathOrName == null || pathOrName.isBlank()) {
            return "";
        }
        String s = pathOrName.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        return slash < 0 ? s : s.substring(slash + 1);
    }

    /**
     * 从 ID 里剥掉最前面的 projectId 段，返回 {@code fileName:line}。
     *
     * <p>用途：报告里引用一条日志时，给人看的是文件位置而不是内部主键。
     * 之所以只提供"去掉首段"而不提供完整拆解 —— projectId 恒为 {@code p-xxxxxxxx} 不含冒号，
     * 因此第一个冒号一定是段边界；而中间段现在也不含冒号了，剩余部分可安全整体保留。</p>
     *
     * @return 解析不了时原样返回入参（宁可不美化，也不要抛异常打断报告生成）
     */
    public static String withoutProject(String docId) {
        if (docId == null || docId.isBlank()) {
            return docId;
        }
        int sep = docId.indexOf(SEP);
        if (sep < 0 || sep + 1 >= docId.length()) {
            return docId;
        }
        return docId.substring(sep + 1);
    }

    /**
     * 校验一个 ID 是否符合三段结构。用于查询前的快速失败判断，避免拿脏 ID 去戳 ES。
     */
    public static boolean isWellFormed(String docId) {
        if (docId == null || docId.isBlank()) {
            return false;
        }
        int first = docId.indexOf(SEP);
        int last = docId.lastIndexOf(SEP);
        return first > 0 && last > first && last < docId.length() - 1;
    }

    /** 便捷重载：直接从文档对象取其 ID（保证与 {@link #of} 同源）。 */
    public static String of(LogEntryDoc doc) {
        return doc.getId();
    }

    private EsDocId() {
    }
}
