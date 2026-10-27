package org.example.tracemind.diagnosis;

import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.common.RedisKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 自动诊断开关 —— 新错误雷达发现新 ERROR 后是否自动触发诊断。
 *
 * <p>状态存 Redis（运行期可切换，重启后保留），Redis 不可用时回落到
 * {@code application.yml} 的 {@code diagnosis.auto-trigger} 默认值。
 * 这样 Redis 挂了不会让雷达跟着瘫，顶多回到默认开关状态。</p>
 */
@Slf4j
@Component
public class AutoDiagnosisSwitch {

    private static final String KEY = RedisKeys.DIAGNOSIS_AUTO_TRIGGER;

    private final StringRedisTemplate redis;
    private final boolean defaultEnabled;
    private final int defaultLimit;

    public AutoDiagnosisSwitch(StringRedisTemplate redis,
                               @Value("${diagnosis.auto-trigger:true}") boolean defaultEnabled,
                               @Value("${diagnosis.auto-trigger-limit:3}") int defaultLimit) {
        this.redis = redis;
        this.defaultEnabled = defaultEnabled;
        this.defaultLimit = defaultLimit;
    }

    /** 当前开关状态。Redis 挂了或没写过 → 回落到 yml 默认值 */
    public boolean isEnabled() {
        try {
            String v = redis.opsForValue().get(KEY);
            if (v != null) {
                return Boolean.parseBoolean(v);
            }
        } catch (Exception e) {
            log.warn("读自动诊断开关失败，回落到默认值 {}。原因={}", defaultEnabled, e.getMessage());
        }
        return defaultEnabled;
    }

    /** 切换开关。写失败只记日志，不抛异常（开关不是关键路径） */
    public void setEnabled(boolean enabled) {
        try {
            redis.opsForValue().set(KEY, String.valueOf(enabled));
        } catch (Exception e) {
            log.warn("写自动诊断开关失败，仅本次生效。enabled={} 原因={}", enabled, e.getMessage());
        }
    }

    /** 每次扫描最多自动诊断的新错误数（防诊断风暴） */
    public int getLimit() {
        return defaultLimit;
    }
}
