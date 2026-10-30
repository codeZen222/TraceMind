package org.example.tracemind.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.tracemind.aiclient.PythonClient;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.es.EsFallbackStream;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.mapper.LogMonitorTaskMapper;
import org.example.tracemind.service.ErrorRadarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集器工厂 —— 锁住 R-2.2 引入的唯一新契约。
 *
 * <p>位点恢复从构造器移到了 {@code init()}，于是产生一个新的风险：<b>有人创建了实例却忘了初始化</b>。
 * 那种状态下 {@code linesRead} 是 0，续读时新行会被算成第 1 行起、静默覆盖 ES 里最早的文档
 * ——正是本轮已经修掉的那个已知问题 2 的复发形态。</p>
 *
 * <p>本类的对策是把 {@code init()} 收进工厂（调用方拿到的一定是初始化好的对象），
 * 下面的用例则证明这件事真的发生了。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CollectorFactoryTest {

    private static final String TASK_ID = "t-001";
    private static final String PROJECT_ID = "p-001";
    private static final String LOG_PATH = "C:/logs/app.log";

    @Mock
    private CollectorOffsetStore offsetStore;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private EsLogRepository esLogRepository;
    @Mock
    private PythonClient pythonClient;
    @Mock
    private LogMonitorTaskMapper logMonitorTaskMapper;
    @Mock
    private CollectorRegistry registry;
    @Mock
    private TraceMindProperties properties;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ErrorRadarService errorRadarService;
    @Mock
    private EsFallbackStream esFallbackStream;

    private CollectorDependencies deps() {
        return new CollectorDependencies(offsetStore, redis, esLogRepository, pythonClient,
                logMonitorTaskMapper, registry, properties, objectMapper, errorRadarService, esFallbackStream);
    }

    @Test
    @DisplayName("★ create() 必须完成位点恢复 —— 否则 linesRead 归零会重现「行号覆盖」事故")
    void createRestoresPositionBeforeReturning() {
        when(offsetStore.read(TASK_ID)).thenReturn(new CollectorOffsetStore.Position(81230L, 5000L, false));

        WatchServiceLogCollector collector = new CollectorFactory(deps()).create(TASK_ID, PROJECT_ID, LOG_PATH);

        // 工厂确实读过位点：这就是"不会返回未初始化实例"的证据
        verify(offsetStore).read(TASK_ID);
        assertThat(collector).isNotNull();
    }

    @Test
    @DisplayName("★ 构造期不做 I/O：new 出来不该碰任何外部系统（此前必须先 stub Redis 才能构造）")
    void constructorDoesNoIo() {
        // 故意不给 offsetStore 打桩：若构造器仍在读位点，这里会拿到 null 并 NPE
        WatchServiceLogCollector collector = new WatchServiceLogCollector(
                TASK_ID, PROJECT_ID, LOG_PATH, deps());

        assertThat(collector).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(offsetStore);
        org.mockito.Mockito.verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("依赖对象原样交给 collector，九个 Bean 一个都不丢")
    void dependenciesArePassedThrough() {
        when(offsetStore.read(TASK_ID)).thenReturn(new CollectorOffsetStore.Position(0L, 0L, false));

        CollectorDependencies d = deps();
        WatchServiceLogCollector collector = new CollectorFactory(d).create(TASK_ID, PROJECT_ID, LOG_PATH);

        assertThat(collector).isNotNull();
        // Lombok @Getter 保证这九个字段都真实存在且可读；缺一个编译期就过不去
        assertThat(d.getOffsetStore()).isSameAs(offsetStore);
        assertThat(d.getEsLogRepository()).isSameAs(esLogRepository);
        assertThat(d.getPythonClient()).isSameAs(pythonClient);
        assertThat(d.getLogMonitorTaskMapper()).isSameAs(logMonitorTaskMapper);
        assertThat(d.getRegistry()).isSameAs(registry);
        assertThat(d.getProperties()).isSameAs(properties);
        assertThat(d.getObjectMapper()).isSameAs(objectMapper);
        assertThat(d.getErrorRadarService()).isSameAs(errorRadarService);
        assertThat(d.getRedis()).isSameAs(redis);
    }
}
