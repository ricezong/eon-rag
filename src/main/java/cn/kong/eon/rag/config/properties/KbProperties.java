package cn.kong.eon.rag.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上下文预算、安全、摄入与增强开关配置。
 */
@ConfigurationProperties(prefix = "kb")
public class KbProperties {

    private final Chat chat = new Chat();
    private final ContextEnrichment contextEnrichment = new ContextEnrichment();
    private final Security security = new Security();
    private final Ingest ingest = new Ingest();

    public Chat getChat() {
        return chat;
    }

    public ContextEnrichment getContextEnrichment() {
        return contextEnrichment;
    }

    public Security getSecurity() {
        return security;
    }

    public Ingest getIngest() {
        return ingest;
    }

    /** 生成层 Token 预算 */
    public static class Chat {
        /** 送入生成模型的检索上下文最大 Token 预算 */
        private int maxContextTokens = 6000;
        /** 为生成模型输出预留的 Token 预算 */
        private int outputReserveTokens = 1000;
        /** SSE 流最长持续时间（毫秒），到时发送 error 事件并中止生成 */
        private int sseTimeoutMs = 120_000;

        public int getMaxContextTokens() {
            return maxContextTokens;
        }

        public void setMaxContextTokens(int maxContextTokens) {
            this.maxContextTokens = maxContextTokens;
        }

        public int getOutputReserveTokens() {
            return outputReserveTokens;
        }

        public void setOutputReserveTokens(int outputReserveTokens) {
            this.outputReserveTokens = outputReserveTokens;
        }

        public int getSseTimeoutMs() {
            return sseTimeoutMs;
        }

        public void setSseTimeoutMs(int sseTimeoutMs) {
            this.sseTimeoutMs = sseTimeoutMs;
        }
    }

    /** 增强层：子块上下文前缀（Contextual Retrieval） */
    public static class ContextEnrichment {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** 安全层：接口鉴权 */
    public static class Security {
        /** 非空时强制校验 X-API-Key 请求头（OPTIONS 预检自动放行） */
        private String apiKey = "";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /** 摄入流水线参数 */
    public static class Ingest {
        /** 应用自管临时目录（MultipartFile 底层临时文件请求返回即清理，须先转存） */
        private String tempDir;

        public String getTempDir() {
            return tempDir;
        }

        public void setTempDir(String tempDir) {
            this.tempDir = tempDir;
        }
    }
}
