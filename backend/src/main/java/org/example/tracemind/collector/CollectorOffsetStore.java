package org.example.tracemind.collector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.common.RedisKeys;
import org.example.tracemind.mapper.LogMonitorTaskMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 采集位点存储 —— 「读到哪了」这件事的唯一出口。
 *
 * <p><b>为什么要有这个类。</b>在此之前，同一个语义有两份实现：
 * {@code WatchServiceLogCollector#offsetFromRedis}（有 try/catch + MySQL 兜底）与
 * {@code MonitorServiceImpl#readOffsetFromRedis}（裸调 Redis）。后者一挂就把
 * start / stop / status 三个接口和删除项目一起打成 50000。两份代码、两种健壮性，
 * 修一处忘一处——这就是这类 bug 的成因。所有位点读写从这里走，降级策略只有一份。</p>
 *
 * <p><b>为什么 offset 和 linesRead 必须放同一个 key。</b>ES 文档 ID 是
 * {@code 项目:文件:行号}，行号由 linesRead 累加得出。两者分开存时一旦只恢复成功一半
 * （典型场景：Redis flushall 后 offset 从 MySQL 兜底恢复到第 5000 行，
 * linesRead 却归零），新读的行会被算成第 1、2 行，<b>静默覆盖 ES 里最早的文档</b>。
 * 现在用一条 Hash 存两个字段，读写都是原子的，不存在半恢复。</p>
 *
 * <p><b>降级原则。</b>Redis 不可用不抛异常，回落 MySQL 快照并返回 {@code degraded=true}，
 * 由调用方如实标给前端。位点是"能读到大概位置"的兜底信息，不值得让整个监控接口挂掉。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectorOffsetStore {

    /** 位点 field 名取自 {@link RedisKeys}；key 本身由 {@link RedisKeys#collectorPosition(String)} 生成 */
    private static final String F_OFFSET = RedisKeys.F_OFFSET;
    private static final String F_LINES = RedisKeys.F_LINES;

    private final StringRedisTemplate redis;
    private final LogMonitorTaskMapper logMonitorTaskMapper;

    /**
     * 读取位点。Redis 优先，缺失或不可用时回落 MySQL 快照。
     *
     * @param taskId 采集任务 id
     * @return 永不为 null；{@link Position#degraded()} 为 true 表示这次读的是兜底值
     */
    public Position read(String taskId) {
        try {
            var hash = redis.opsForHash().entries(RedisKeys.collectorPosition(taskId));
            if (!hash.isEmpty()) {
                Long offset = parse(hash.get(F_OFFSET));
                Long lines = parse(hash.get(F_LINES));
                // Redis 里有记录就以它为准；个别 field 缺失时单独回落，不影响另一个
                return new Position(
                        offset != null ? offset : mysqlOffset(taskId),
                        lines != null ? lines : mysqlLines(taskId),
                        false);
            }
        } catch (Exception e) {
            log.warn("读 Redis 位点失败，回落 MySQL 快照。taskId={} 原因={}", taskId, e.getMessage());
            return new Position(mysqlOffset(taskId), mysqlLines(taskId), true);
        }
        // Redis 正常但没数据（首次启动 / key 过期）→ 同样回落快照，但不算故障降级
        return new Position(mysqlOffset(taskId), mysqlLines(taskId), false);
    }

    /**
     * 写入位点。一次 HMSET 同时推进 offset 与 lines，保证不会出现半恢复。
     *
     * <p>写失败只记日志不外抛：采集线程不能因为 Redis 抖动就中断读取。
     * 代价是这段进度暂时只在内存里，下次重启可能重读或漏读若干行 ——
     * 比整个采集线程死掉、MySQL 悬空 running 要轻。</p>
     */
    public void write(String taskId, long offset, long lines) {
        try {
            String key = RedisKeys.collectorPosition(taskId);
            redis.opsForHash().put(key, F_OFFSET, String.valueOf(offset));
            redis.opsForHash().put(key, F_LINES, String.valueOf(lines));
        } catch (Exception e) {
            log.warn("写 Redis 位点失败，本次仅内存推进。taskId={} 原因={}", taskId, e.getMessage());
        }
    }

    /**
     * 清理任务的全部 Redis 痕迹（位点 Hash + 文件指纹）。删除项目时必须调用。
     *
     * <p>⚠️ 这两类 key <b>都没有 TTL</b>（{@link #write} 只做两次 put，指纹同理），
     * 这里是唯一的回收入口。不调用就会随项目删除无限累积孤儿 key ——
     * 别指望过期兜底，那个机制不存在。</p>
     */
    public void clear(String taskId) {
        try {
            redis.delete(RedisKeys.collectorPosition(taskId));
            redis.delete(RedisKeys.collectorFingerprint(taskId));
        } catch (Exception e) {
            log.warn("清理 Redis 位点失败 taskId={} 原因={}", taskId, e.getMessage());
        }
    }

    // ==================================================================
    // 内部：MySQL 兜底
    // ==================================================================

    private long mysqlOffset(String taskId) {
        var task = safeSelect(taskId);
        return task != null && task.getOffset() != null && task.getOffset() > 0 ? task.getOffset() : 0L;
    }

    private long mysqlLines(String taskId) {
        var task = safeSelect(taskId);
        return task != null && task.getLinesRead() != null && task.getLinesRead() > 0 ? task.getLinesRead() : 0L;
    }

    /** 兜底路径自己也不能抛——否则「Redis 挂了」会变成「Redis 和 MySQL 一起挂了才报错」 */
    private org.example.tracemind.pojo.entity.LogMonitorTask safeSelect(String taskId) {
        try {
            return logMonitorTaskMapper.selectById(taskId);
        } catch (Exception e) {
            log.warn("读 MySQL 位点快照失败 taskId={} 原因={}", taskId, e.getMessage());
            return null;
        }
    }

    private Long parse(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            log.warn("位点字段不是数字，忽略该值。value={}", v);
            return null;
        }
    }

    /**
     * 位点快照。
     *
     * @param offset   已读字节偏移
     * @param lines    累计已读行数（ES 文档行号的基准）
     * @param degraded true 表示 Redis 不可用、这两个值来自 MySQL 兜底快照
     */
    public record Position(long offset, long lines, boolean degraded) {
    }
}
