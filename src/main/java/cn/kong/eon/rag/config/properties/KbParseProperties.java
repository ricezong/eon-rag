package cn.kong.eon.rag.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 解析层参数。
 */
@ConfigurationProperties(prefix = "kb.parse")
public class KbParseProperties {

    private final Cloud cloud = new Cloud();
    /** 上传文件大小上限（字节），默认 50MB */
    private long maxFileSize = 52_428_800;
    /** 整篇有效字符低于该值判定疑似扫描件 */
    private int minTextChars = 50;

    /** 解析路由模式：AUTO（白名单分流）/ LOCAL（强制本地）/ CLOUD（强制云端） */
    private String mode = "AUTO";

    /** AUTO 模式下优先分流至云端的格式白名单（小写扩展名） */
    private java.util.List<String> cloudTypes =
            java.util.List.of("pdf", "doc", "docx", "ppt", "pptx");

    public Cloud getCloud() {
        return cloud;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public int getMinTextChars() {
        return minTextChars;
    }

    public void setMinTextChars(int minTextChars) {
        this.minTextChars = minTextChars;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public java.util.List<String> getCloudTypes() {
        return cloudTypes;
    }

    public void setCloudTypes(java.util.List<String> cloudTypes) {
        this.cloudTypes = cloudTypes;
    }

    public static class Cloud {
        /** 是否启用云端 LlamaParse 解析客户端（需 LLAMA_CLOUD_API_KEY） */
        private boolean enabled = false;
        private String apiKey = "";
        private String baseUrl = "https://api.cloud.llamaindex.ai";
        /** 解析档位：fast / cost_effective / agentic / agentic_plus */
        private String tier = "cost_effective";
        /** 解析接口版本：latest 或具体日期版本 */
        private String version = "latest";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

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

        public String getTier() {
            return tier;
        }

        public void setTier(String tier) {
            this.tier = tier;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }
}
