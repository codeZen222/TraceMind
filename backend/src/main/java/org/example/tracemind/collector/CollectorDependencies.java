package org.example.tracemind.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.example.tracemind.aiclient.PythonClient;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.es.EsFallbackStream;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.mapper.LogMonitorTaskMapper;
import org.example.tracemind.service.ErrorRadarService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 采集线程需要的<b>全部容器 Bean</b>，打包成一个对象。
 *
 * <h3>为什么要有这个类</h3>
 * {@link WatchServiceLogCollector} 是每任务一个实例的普通 {@code Runnable}，不能做成单例 Bean，
 * 所以它的依赖过去靠调用方手工逐个传 —— 构造器 12 个参数、其中 9 个是容器 Bean。
 * 那带来三个编译器抓不到的问题：
 * <ol>
 *   <li><b>位置错注</b>：多个同类型参数相邻（{@code CollectorOffsetStore} / {@code StringRedisTemplate} /
 *       {@code EsLogRepository}…），调换顺序照样编译通过，运行期才炸或直接静默错行为。</li>
 *   <li><b>清单存三份</b>：调用方的字段、调用处的实参、collector 自己的字段。新增一个依赖要改三处，
 *       漏掉中间那处没有任何提示。</li>
 *   <li><b>依赖穿透</b>：{@code MonitorServiceImpl} 为了转交这些 Bean，自己持有了一堆
 *       它压根不用的字段（EsLogRepository、PythonClient、ErrorRadarService…），service 层依赖面虚胖。</li>
 * </ol>
 *
 * <p>收成一个具名对象后：per-task 的值只剩 3 个（taskId / projectId / filePath），
 * 由 {@link CollectorFactory#create} 显式传入；容器 Bean 由 Spring 按类型注入本类，
 * <b>缺依赖时容器启动即失败</b>，而不是运行期 NPE。</p>
 *
 * <p>⚠️ 这里刻意不做成 {@code @Scope("prototype")} + {@code getBean(taskId, ...)}：
 * 那样只是把服务定位器换个地方放，并没有消失；且原型 Bean 默认不受容器生命周期管理，
 * {@code stop()} 语义仍要靠 Registry。工厂方案无框架魔法，collector 仍是可独立单测的 POJO。</p>
 */
@Getter
@Component
@RequiredArgsConstructor
public class CollectorDependencies {

    /** 位点读写（offset + lines 原子推进） */
    private final CollectorOffsetStore offsetStore;

    /** 仅用于文件指纹这一辅助 key；位点一律走 offsetStore */
    private final StringRedisTemplate redis;

    private final EsLogRepository esLogRepository;

    /** 模板提取；不可用时熔断降级，日志照常入库 */
    private final PythonClient pythonClient;

    /** MySQL 兜底快照的出口 */
    private final LogMonitorTaskMapper logMonitorTaskMapper;

    /** 线程登记表，供 stop() 找到那个线程 */
    private final CollectorRegistry registry;

    private final TraceMindProperties properties;

    /** 兜底文件逐行序列化用 */
    private final ObjectMapper objectMapper;

    /** 新错误雷达：每批 ERROR 的 clusterId 喂给它判新 */
    private final ErrorRadarService errorRadarService;

    /** ES 写失败兜底流（Stream 主路径；Redis 挂时生产端退回文件方案） */
    private final EsFallbackStream esFallbackStream;
}
