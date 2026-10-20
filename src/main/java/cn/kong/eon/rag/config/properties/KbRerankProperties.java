package cn.kong.eon.rag.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 百炼重排参数。端点前缀 /compatible-api/v1，与 Embedding 的 /compatible-mode 不同 */
@ConfigurationProperties(prefix = "kb.rerank")
public class KbRerankProperties {

    private String apiKey = "";
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-api/v1";
    private String model = "qwen3-rerank";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
