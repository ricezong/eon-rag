package cn.kong.eon.rag.config;

import cn.kong.eon.rag.config.properties.KbEmbeddingProperties;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量模型配置。
 * Embedding 与 Chat 使用独立配置与密钥，自行装配而不复用 Spring AI 的向量化自动配置。
 */
@Configuration
public class AiModelConfig {

    private final KbEmbeddingProperties properties;

    public AiModelConfig(KbEmbeddingProperties properties) {
        this.properties = properties;
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(properties.getModel())
                .dimensions(properties.getDimensions())
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }
}
