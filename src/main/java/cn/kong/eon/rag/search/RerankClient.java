package cn.kong.eon.rag.search;

import cn.kong.eon.rag.config.properties.KbRerankProperties;
import cn.kong.eon.rag.repo.entity.ChildChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 百炼 qwen3-rerank 云端重排客户端。
 *
 * 端点前缀为 /compatible-api/v1（与 Embedding 的 /compatible-mode/v1 不同），
 * 完整 URL 由 kb.rerank.base-url 配置。
 * 超时或异常向上抛出，由编排层降级为 RRF 前 N。
 */
@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public RerankClient(KbRerankProperties properties, ObjectMapper objectMapper) {
        this.model = properties.getModel();
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .requestFactory(factory)
                .build();
        log.info("RerankClient initialized: baseUrl={}, model={}", properties.getBaseUrl(), model);
    }

    /**
     * @return 按相关性降序的候选子块（截取 topN，rawScore=relevance_score）
     */
    public List<ChildChunk> rerank(String query, List<ChildChunk> candidates, int topN) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        long startedAt = System.nanoTime();
        List<String> documents = candidates.stream().map(ChildChunk::getContent).toList();
        log.debug("rerank stage=request candidates={} topN={} queryLength={}",
                candidates.size(), topN, query == null ? 0 : query.length());
        log.debug("rerank candidates ids={}", candidates.stream().map(ChildChunk::getId).toList());
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "query", query,
                    "documents", documents,
                    "top_n", topN));

            String response = restClient.post()
                    .uri("/reranks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            List<ChildChunk> ranked = parseResults(response, candidates, topN);
            log.debug("rerank stage=done candidates={} returned={} topScore={} elapsedMs={}",
                    candidates.size(), ranked.size(),
                    ranked.isEmpty() ? 0.0 : ranked.get(0).getRawScore(),
                    elapsedMs(startedAt));
            log.debug("rerank top ids/scores={}", ranked.stream()
                    .limit(10)
                    .map(item -> item.getId() + ":" + item.getRawScore())
                    .toList());
            return ranked;
        } catch (Exception e) {
            log.warn("rerank stage=failed candidates={} elapsedMs={} reason={}",
                    candidates.size(), elapsedMs(startedAt), e.getMessage());
            throw new IllegalStateException("rerank failed: " + e.getMessage(), e);
        }
    }

    /** results[].index/relevance_score → 候选映射 */
    private List<ChildChunk> parseResults(String response, List<ChildChunk> candidates, int topN)
            throws Exception {
        JsonNode results = objectMapper.readTree(response).path("results");
        List<ChildChunk> ranked = new ArrayList<>();
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= candidates.size()) {
                continue;
            }
            ChildChunk child = candidates.get(index);
            ranked.add(child.copyWithScore(item.path("relevance_score").asDouble(0.0)));
            if (ranked.size() >= topN) {
                break;
            }
        }
        return ranked;
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
