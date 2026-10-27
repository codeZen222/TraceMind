package org.example.tracemind.collector;

import org.example.tracemind.common.RedisKeys;
import org.example.tracemind.mapper.LogMonitorTaskMapper;
import org.example.tracemind.pojo.entity.LogMonitorTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 采集位点存储的单测。
 *
 * <p>这里锁住两条曾经真出过问题的行为：</p>
 * <ol>
 *   <li><b>Redis 挂了不能抛</b>。原 {@code MonitorServiceImpl#readOffsetFromRedis} 裸调 Redis，
 *       一次连接失败就把 start / stop / status 三个接口和删除项目一起打成 50000。</li>
 *   <li><b>offset 与 linesRead 同源恢复</b>。行号归零会让新日志的 ES 文档 ID 撞上前 N 行、静默覆盖。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class CollectorOffsetStoreTest {

    private static final String TASK_ID = "t-001";
    /**
     * ★ 引用 RedisKeys 而非硬编码字面量：key 命名变了这里自动跟上，
     *   不会出现「生产代码查新 key、测试 stub 挂旧 key」这种假失败。
     */
    private static final String KEY = RedisKeys.collectorPosition(TASK_ID);

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private LogMonitorTaskMapper mapper;

    private CollectorOffsetStore store;

    @BeforeEach
    void setUp() {
        store = new CollectorOffsetStore(redis, mapper);
    }

    private LogMonitorTask snapshot(long offset, long lines) {
        LogMonitorTask task = new LogMonitorTask();
        task.setTaskId(TASK_ID);
        task.setOffset(offset);
        task.setLinesRead(lines);
        return task;
    }

    @Test
    @DisplayName("Redis 正常：以 Hash 里的实时位点为准，不查 MySQL")
    void readsPositionFromRedisWhenAvailable() {
        when(redis.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(KEY)).thenReturn(Map.of(
                RedisKeys.F_OFFSET, "8123", RedisKeys.F_LINES, "120"));

        CollectorOffsetStore.Position pos = store.read(TASK_ID);

        assertThat(pos.offset()).isEqualTo(8123L);
        assertThat(pos.lines()).isEqualTo(120L);
        assertThat(pos.degraded()).isFalse();
    }

    @Test
    @DisplayName("★ Redis 连接失败：回落 MySQL 快照并标 degraded，绝不外抛异常")
    void fallsBackToMysqlSnapshotWhenRedisDown() {
        when(redis.opsForHash()).thenThrow(new RedisConnectionFailureException("Connection refused"));
        when(mapper.selectById(TASK_ID)).thenReturn(snapshot(8123L, 120L));

        CollectorOffsetStore.Position pos = store.read(TASK_ID);

        assertThat(pos.offset()).isEqualTo(8123L);
        assertThat(pos.lines()).isEqualTo(120L);
        assertThat(pos.degraded()).isTrue();
    }

    @Test
    @DisplayName("Redis 正常但无记录（首次启动）：回落快照，但不算故障降级")
    void treatsMissingKeyAsColdStartNotDegraded() {
        when(redis.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(KEY)).thenReturn(Map.of());
        when(mapper.selectById(TASK_ID)).thenReturn(snapshot(0L, 0L));

        CollectorOffsetStore.Position pos = store.read(TASK_ID);

        assertThat(pos.offset()).isZero();
        assertThat(pos.degraded()).isFalse();
    }

    @Test
    @DisplayName("★ 兜底路径自身也失败（MySQL 也不通）：返回零点而不是把异常抛给接口层")
    void returnsZeroPositionWhenBothStoresUnavailable() {
        when(redis.opsForHash()).thenThrow(new RedisConnectionFailureException("down"));
        when(mapper.selectById(TASK_ID)).thenThrow(new RuntimeException("db down"));

        CollectorOffsetStore.Position pos = store.read(TASK_ID);

        assertThat(pos.offset()).isZero();
        assertThat(pos.lines()).isZero();
        assertThat(pos.degraded()).isTrue();
    }

    @Test
    @DisplayName("脏数据防御：非数字的位点字段忽略该值，单个 field 缺失不影响另一个")
    void ignoresUnparsableFieldsIndividually() {
        when(redis.opsForHash()).thenReturn(hashOperations);
        // offset 是脏值 → 单独回落；lines 正常 → 直接采用
        when(hashOperations.entries(KEY)).thenReturn(Map.of(
                RedisKeys.F_OFFSET, "not-a-number", RedisKeys.F_LINES, "77"));
        when(mapper.selectById(TASK_ID)).thenReturn(snapshot(9999L, 77L));

        CollectorOffsetStore.Position pos = store.read(TASK_ID);

        assertThat(pos.offset()).isEqualTo(9999L);
        assertThat(pos.lines()).isEqualTo(77L);
    }

    @Test
    @DisplayName("★ 写位点时 Redis 抖动只记日志：不能让采集线程因为写失败而退出")
    void writeSwallowsRedisFailure() {
        when(redis.opsForHash()).thenThrow(new RedisConnectionFailureException("down"));

        store.write(TASK_ID, 4096L, 60L);
        // 走到这里没抛出即通过
    }
}
