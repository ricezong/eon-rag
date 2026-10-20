package cn.kong.eon.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用入口。
 * 摄入与检索线程池由 AsyncExecutorConfig 显式声明并按名注入；
 * 向量模型由 AiModelConfig 装配，故排除 Spring AI 的向量化自动配置。
 */
@SpringBootApplication(exclude = {OpenAiEmbeddingAutoConfiguration.class})
@MapperScan("cn.kong.eon.rag.repo.mapper")
@ConfigurationPropertiesScan("cn.kong.eon.rag.config.properties")
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
