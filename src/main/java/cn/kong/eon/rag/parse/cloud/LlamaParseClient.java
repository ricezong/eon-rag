package cn.kong.eon.rag.parse.cloud;

import ai.llamaindex.llamacloud.client.LlamaCloudClient;
import ai.llamaindex.llamacloud.client.okhttp.LlamaCloudOkHttpClient;
import ai.llamaindex.llamacloud.core.MultipartField;
import ai.llamaindex.llamacloud.models.files.FileCreateParams;
import ai.llamaindex.llamacloud.models.files.FileCreateResponse;
import ai.llamaindex.llamacloud.models.parsing.ParsingCreateParams;
import ai.llamaindex.llamacloud.models.parsing.ParsingCreateResponse;
import ai.llamaindex.llamacloud.models.parsing.ParsingGetParams;
import ai.llamaindex.llamacloud.models.parsing.ParsingGetResponse;
import cn.kong.eon.rag.common.exception.BizException;
import cn.kong.eon.rag.common.exception.ErrorCode;
import cn.kong.eon.rag.config.properties.KbParseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.stream.Collectors;

/**
 * LlamaParse 云端解析客户端（官方 Java SDK）：
 * 上传文件 → 创建解析任务 → 轮询至完成（expand=markdown）→ 取按页 Markdown 拼接。
 * 仅 kb.parse.cloud.enabled=true 时装配。
 */
@Component
@ConditionalOnProperty(prefix = "kb.parse.cloud", name = "enabled", havingValue = "true")
public class LlamaParseClient {

    private static final Logger log = LoggerFactory.getLogger(LlamaParseClient.class);

    private final LlamaCloudClient client;
    private final KbParseProperties properties;

    public LlamaParseClient(KbParseProperties properties) {
        KbParseProperties.Cloud cloud = properties.getCloud();
        this.properties = properties;
        this.client = LlamaCloudOkHttpClient.builder()
                .baseUrl(cloud.getBaseUrl())
                .apiKey(cloud.getApiKey())
                .build();
    }

    /** 统一入口：上传 + 轮询，返回全量 Markdown */
    public String parseToMarkdown(byte[] bytes, String fileName) {
        String fileId = uploadFile(bytes, fileName);
        String jobId = createJob(fileId);
        return pollMarkdown(jobId, Duration.ofMinutes(5), Duration.ofSeconds(2));
    }

    private String uploadFile(byte[] bytes, String fileName) {
        try {
            MultipartField<InputStream> upload = MultipartField.<InputStream>builder()
                    .value(new ByteArrayInputStream(bytes))
                    .filename(fileName)
                    .build();
            FileCreateResponse response = client.files()
                    .create(FileCreateParams.builder()
                            .file(upload)
                            .purpose("parse")
                            .build());
            return response.id();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARSE_FAILED, "llamaparse upload failed: " + e.getMessage(), e);
        }
    }

    private String createJob(String fileId) {
        try {
            ParsingCreateResponse response = client.parsing().create(ParsingCreateParams.builder()
                    .body(ParsingCreateParams.Body.builder()
                            .fileId(fileId)
                            .tier(properties.getCloud().getTier())
                            .version(properties.getCloud().getVersion())
                            .build())
                    .build());
            return response.id();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARSE_FAILED, "llamaparse create failed: " + e.getMessage(), e);
        }
    }

    /** 轮询任务状态至终态；COMPLETED 返回按页拼接的 Markdown */
    private String pollMarkdown(String jobId, Duration timeout, Duration interval) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                ParsingGetResponse response = client.parsing().get(ParsingGetParams.builder()
                        .jobId(jobId)
                        .addExpand("markdown")
                        .build());
                ParsingGetResponse.Job.Status status = response.job().status();
                // SDK 的状态枚举反序列化后是新实例，须用 equals 比较
                if (ParsingGetResponse.Job.Status.COMPLETED.equals(status)) {
                    return extractMarkdown(response);
                }
                if (ParsingGetResponse.Job.Status.FAILED.equals(status)
                        || ParsingGetResponse.Job.Status.CANCELLED.equals(status)) {
                    String error = response.job().errorMessage().orElse("unknown");
                    throw new BizException(ErrorCode.PARSE_FAILED, "llamaparse job " + status + ": " + error);
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                // 单次轮询网络异常不中断，继续等待
                log.warn("llamaparse poll error, retrying: {}", e.getMessage());
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BizException(ErrorCode.PARSE_FAILED, "llamaparse poll interrupted");
            }
        }
        throw new BizException(ErrorCode.PARSE_FAILED, "llamaparse job timeout: " + jobId);
    }

    /** markdownFull 优先；缺失时按页拼接 */
    private String extractMarkdown(ParsingGetResponse response) {
        String full = response.markdownFull().orElse(null);
        if (full != null && !full.isBlank()) {
            return full;
        }
        return response.markdown()
                .map(md -> md.pages().stream()
                        .map(page -> page.markdownResult().map(r -> r.markdown()).orElse(""))
                        .filter(text -> !text.isBlank())
                        .collect(Collectors.joining("\n\n")))
                .orElse(null);
    }
}
