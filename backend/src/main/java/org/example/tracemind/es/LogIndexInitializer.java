package org.example.tracemind.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * 启动时确保 log_entry 索引存在。
 *
 * <p>失败只记日志、不抛异常——ES 没起的时候整个应用还是要能跑起来的
 * （设置页、项目 CRUD 都不依赖 ES）。</p>
 */
@Slf4j
@Component
//@RequiredArgsConstructor
public class LogIndexInitializer implements ApplicationRunner {

    public static final String INDEX = "log_entry";
    @Autowired
    private  ElasticsearchClient client;

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean exists = client.indices().exists(e -> e.index(INDEX)).value();
            if (exists) {
                log.info("ES 索引 {} 已存在，跳过创建", INDEX);
                return;
            }
            // 直接用 resources/db/es-log_entry-index.json，避免 mapping 写两遍
            try (Reader reader = new InputStreamReader(
                    new ClassPathResource("db/es-log_entry-index.json").getInputStream(),
                    StandardCharsets.UTF_8)) {
                client.indices().create(c -> c.index(INDEX).withJson(reader));
            }
            log.info("ES 索引 {} 创建成功", INDEX);
        } catch (Exception e) {
            log.error("ES 索引初始化失败：ES 没起？还是没装 ik 分词器？J3 的接口会不可用", e);
        }
    }
}