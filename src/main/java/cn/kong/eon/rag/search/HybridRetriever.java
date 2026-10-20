package cn.kong.eon.rag.search;

import cn.kong.eon.rag.common.util.EmbeddingSupport;
import cn.kong.eon.rag.config.properties.KbSearchProperties;
import cn.kong.eon.rag.repo.entity.ChildChunk;
import cn.kong.eon.rag.repo.mapper.ChildChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 三路并行召回：向量余弦 / zhparser 全文 / pg_trgm 模糊。
 *
 * 任一通道失败仅丢弃该路（记 warn），不整体失败。
 * 模糊通道的 SET LOCAL 与查询必须同事务（同连接），用 TransactionTemplate 包裹；
 * 三路在各线程独立连接，互不影响。
 */
@Service
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final ChildChunkMapper childChunkMapper;
    private final EmbeddingModel embeddingModel;
    private final KbSearchProperties props;
    private final TransactionTemplate readOnlyTx;
    private final TaskExecutor searchExecutor;

    public HybridRetriever(ChildChunkMapper childChunkMapper,
                             EmbeddingModel embeddingModel,
                             KbSearchProperties props,
                             PlatformTransactionManager txManager,
                             @Qualifier("searchExecutor") TaskExecutor searchExecutor) {
        this.childChunkMapper = childChunkMapper;
        this.embeddingModel = embeddingModel;
        this.props = props;
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
        this.searchExecutor = searchExecutor;
    }

    /**
     * 三路并行召回。
     *
     * @param question 原始提问（全文与模糊通道输入）
     * @param vectorInput 向量通道输入（HyDE 改写文本或原始提问）
     * @param kbIds 知识库范围；为空表示全库
     * @param queryId 检索日志关联 ID
     */
    public List<List<ChildChunk>> search(String question, String vectorInput,
                                         List<UUID> kbIds, String queryId) {
        long embedStarted = System.nanoTime();
        float[] queryEmbedding = embeddingModel.embed(vectorInput);
        String queryVector = EmbeddingSupport.toPgVector(queryEmbedding);
        log.info("retrieval stage=embed.query queryId={} dimensions={} elapsedMs={}",
                queryId, queryEmbedding.length, elapsedMs(embedStarted));

        CompletableFuture<List<ChildChunk>> vectorFuture = CompletableFuture.supplyAsync(
                () -> timedChannel(queryId, "vector", props.getVectorTopK(),
                        () -> childChunkMapper.searchByVector(queryVector, kbIds, props.getVectorTopK())),
                searchExecutor);

        CompletableFuture<List<ChildChunk>> fulltextFuture = CompletableFuture.supplyAsync(
                () -> timedChannel(queryId, "fulltext", props.getFulltextTopK(),
                        () -> childChunkMapper.searchByFulltext(question, kbIds, props.getFulltextTopK())),
                searchExecutor);

        CompletableFuture<List<ChildChunk>> fuzzyFuture = CompletableFuture.supplyAsync(
                () -> timedChannel(queryId, "fuzzy", props.getFuzzyTopK(),
                        () -> readOnlyTx.execute(status -> {
                            childChunkMapper.setFuzzyThresholdLocal(props.getFuzzySimilarityThreshold());
                            return childChunkMapper.searchByFuzzy(question, kbIds, props.getFuzzyTopK());
                        })),
                searchExecutor);

        List<List<ChildChunk>> channels = new ArrayList<>(3);
        joinChannel(queryId, "vector", vectorFuture, channels);
        joinChannel(queryId, "fulltext", fulltextFuture, channels);
        joinChannel(queryId, "fuzzy", fuzzyFuture, channels);
        return channels;
    }

    private List<ChildChunk> timedChannel(String queryId, String name, int topK,
                                          Supplier<List<ChildChunk>> query) {
        long started = System.nanoTime();
        List<ChildChunk> hits = query.get();
        Double firstScore = hits == null || hits.isEmpty() ? null : hits.get(0).getRawScore();
        log.info("retrieval channel={} queryId={} hits={} topK={} topScore={} elapsedMs={}",
                name, queryId, hits == null ? 0 : hits.size(), topK,
                firstScore == null ? 0.0 : firstScore, elapsedMs(started));
        return hits;
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private void joinChannel(String queryId, String name,
                             CompletableFuture<List<ChildChunk>> future,
                             List<List<ChildChunk>> channels) {
        try {
            List<ChildChunk> hits = future.join();
            if (hits != null && !hits.isEmpty()) {
                channels.add(hits);
            }
        } catch (Exception e) {
            // 单路失败仅降级为双路/单路融合
            log.warn("retrieval channel={} queryId={} failed, degraded: {}",
                    name, queryId, e.getMessage());
        }
    }
}
