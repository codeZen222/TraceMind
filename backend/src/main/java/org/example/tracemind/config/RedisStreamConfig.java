package org.example.tracemind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 专门给 Stream 用的 {@link RedisTemplate}。
 *
 * <h3>为什么不用现成的 StringRedisTemplate</h3>
 * 项目里注入的 {@code StringRedisTemplate}（{@code RedisTemplate<String,String>}）会把 hash 的
 * field / value 都当成普通 String，读 {@code LogEntryDoc} 还得自己再 {@code JSON.parse} 一次，
 * 且泛型不友好。这里配 Jackson 序列化，让 {@code opsForStream()} 直接返回
 * {@code ObjectRecord<String, LogEntryDoc>}，类型安全。
 *
 * <h3>⚠️ 别用它替代业务的 StringRedisTemplate</h3>
 * 本 bean 的 value 会被 Jackson 包上引号，与位点/seq/ticket 这类普通字符串 key 的行为不同，
 * 混用会让那些 key 读出来带引号、破坏现有逻辑。所以它只给 Stream 用（见
 * {@code EsFallbackStream}），业务 key 一律继续走 {@code StringRedisTemplate}。
 */
@Configuration
public class RedisStreamConfig {

    /**
     * Stream 专用模板。key（stream 名 / 组名 / 消费者名）用 String，
     * hash field 名用 String，hash value 用 Jackson 自动处理 {@code LogEntryDoc} ↔ JSON。
     */
    @Bean
    public RedisTemplate<String, Object> streamRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        GenericJackson2JsonRedisSerializer json = new GenericJackson2JsonRedisSerializer();
        template.setHashValueSerializer(json);
        template.setValueSerializer(json);

        template.afterPropertiesSet();
        return template;
    }
}