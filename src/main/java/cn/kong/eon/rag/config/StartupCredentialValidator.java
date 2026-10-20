package cn.kong.eon.rag.config;

import cn.kong.eon.rag.config.properties.KbEmbeddingProperties;
import cn.kong.eon.rag.config.properties.KbParseProperties;
import cn.kong.eon.rag.config.properties.KbRerankProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动期凭证校验：核心模型与数据库凭证缺失时立即失败，避免使用空 Key 运行。
 */
@Component
public class StartupCredentialValidator {

    public StartupCredentialValidator(
            @Value("${spring.ai.openai.api-key:}") String chatApiKey,
            @Value("${spring.datasource.password:}") String databasePassword,
            KbEmbeddingProperties embedding,
            KbRerankProperties rerank,
            KbParseProperties parse) {
        require(chatApiKey, "KB_CHAT_API_KEY");
        require(databasePassword, "KB_DB_PASSWORD");
        require(embedding.getApiKey(), "DASHSCOPE_EMBEDDING_API_KEY");
        require(rerank.getApiKey(), "DASHSCOPE_RERANK_API_KEY");
        if (parse.getCloud().isEnabled()) {
            require(parse.getCloud().getApiKey(), "LLAMA_CLOUD_API_KEY");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 未配置");
        }
    }
}
