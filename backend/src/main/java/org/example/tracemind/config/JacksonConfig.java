package org.example.tracemind.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局配置：把前端契约钉死在代码里。
 *
 * <p>Spring Boot 3.x 使用 Jackson 2.x（{@code com.fasterxml.jackson.databind.*}），
 * 扩展点是 {@link Jackson2ObjectMapperBuilderCustomizer}。</p>
 *
 * <p>注意<b>不要</b>在这里加全局 {@code NON_NULL}：契约里 {@code "fileCount": null}、
 * {@code "matchedAnalyzer": null} 是明着写的，全局丢 null 会让这些 key 直接消失。</p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer traceMindJsonCustomizer() {
        return builder -> builder
                .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .featuresToDisable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
