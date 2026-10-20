package cn.kong.eon.rag.search;

import cn.kong.eon.rag.chunk.TokenCounter;
import cn.kong.eon.rag.config.properties.KbProperties;
import cn.kong.eon.rag.config.properties.KbSearchProperties;
import cn.kong.eon.rag.repo.entity.ChildChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 检索编排：三路并行 → RRF 融合 Top50 → 云端 Rerank Top15（失败降级 RRF 前 15）
 * → 父块归并 + Token 预算裁剪。各阶段耗时埋点输出。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** 模板固定开销估算（指令 + 标签 + 引用编号），从预算中预留 */
    private static final int TEMPLATE_OVERHEAD_TOKENS = 200;

    private final HybridRetriever hybridRetriever;
    private final RrfFuser rrfFuser;
    private final RerankClient rerankClient;
    private final ParentAggregator parentAggregator;
    private final HydeRewriter hydeRewriter;
    private final KbSearchProperties searchProps;
    private final KbProperties kbProps;
    private final TokenCounter tokenCounter;

    public RetrievalService(HybridRetriever hybridRetriever,
                            RrfFuser rrfFuser,
                            RerankClient rerankClient,
                            ParentAggregator parentAggregator,
                            HydeRewriter hydeRewriter,
                            KbSearchProperties searchProps,
                            KbProperties kbProps,
                            TokenCounter tokenCounter) {
        this.hybridRetriever = hybridRetriever;
        this.rrfFuser = rrfFuser;
        this.rerankClient = rerankClient;
        this.parentAggregator = parentAggregator;
        this.hydeRewriter = hydeRewriter;
        this.searchProps = searchProps;
        this.kbProps = kbProps;
        this.tokenCounter = tokenCounter;
    }

    public List<ContextSegment> retrieve(String question, List<UUID> kbIds) {
        String queryId = UUID.randomUUID().toString().substring(0, 8);
        long retrieveStarted = System.nanoTime();
        log.info("retrieval start queryId={} questionLength={} kbCount={} hyde={}",
                queryId, question == null ? 0 : question.length(),
                kbIds == null ? 0 : kbIds.size(), searchProps.isHydeEnabled());

        // 1. HyDE 改写（仅向量通道使用；失败降级原提问）
        String vectorInput = question;
        if (searchProps.isHydeEnabled()) {
            long hydeStarted = System.nanoTime();
            String rewritten = hydeRewriter.rewrite(question);
            if (rewritten != null) {
                vectorInput = rewritten;
            }
            log.info("retrieval stage=hyde queryId={} rewritten={} elapsedMs={}",
                    queryId, rewritten != null, elapsedMs(hydeStarted));
        }

        // 2. 三路并行召回（计时）
        long t0 = System.nanoTime();
        List<List<ChildChunk>> channels = hybridRetriever.search(question, vectorInput, kbIds, queryId);
        long recallMs = elapsedMs(t0);
        log.info("retrieval stage=recall queryId={} channels={} hits={} elapsedMs={}",
                queryId, channels.size(),
                channels.stream().mapToInt(List::size).sum(), recallMs);

        // 3. RRF 融合 Top 50
        List<ChildChunk> fused = rrfFuser.fuse(channels, searchProps.getRrfK(), searchProps.getRrfTopN());
        if (fused.isEmpty()) {
            log.info("retrieval stage=rrf queryId={} fused=0 recallMs={} totalMs={}",
                    queryId, recallMs, elapsedMs(retrieveStarted));
            return List.of();
        }
        log.info("retrieval stage=rrf queryId={} fused={} topScore={}",
                queryId, fused.size(), fused.get(0).getRawScore());

        // 4. 云端重排 Top 15；失败降级 RRF 前 15（高可用，计时）
        long t1 = System.nanoTime();
        List<ChildChunk> reranked;
        double hitBonusWeight = searchProps.getParentHitBonusWeight();
        try {
            log.info("retrieval stage=rerank.start queryId={} candidates={} topN={}",
                    queryId, fused.size(), searchProps.getRerankTopN());
            reranked = rerankClient.rerank(question, fused, searchProps.getRerankTopN());
        } catch (Exception e) {
            log.warn("retrieval stage=rerank.fallback queryId={} candidates={} reason={}",
                    queryId, fused.size(), e.getMessage());
            reranked = fused.subList(0, Math.min(searchProps.getRerankTopN(), fused.size()));
            hitBonusWeight = 0.0;
        }
        long rerankMs = elapsedMs(t1);
        log.info("retrieval stage=rerank.done queryId={} returned={} elapsedMs={} topScore={}",
                queryId, reranked.size(), rerankMs,
                reranked.isEmpty() ? 0.0 : reranked.get(0).getRawScore());

        // 5. 预算 = 总预算 - 输出预留 - 模板开销 - 问题本身
        int budget = kbProps.getChat().getMaxContextTokens()
                - kbProps.getChat().getOutputReserveTokens()
                - TEMPLATE_OVERHEAD_TOKENS
                - tokenCounter.count(question);
        List<ContextSegment> segments = parentAggregator.aggregate(
                reranked, Math.max(budget, 0), hitBonusWeight);
        log.info("retrieval stage=aggregate queryId={} segments={} budget={} usedTokens={} totalMs={}",
                queryId, segments.size(), Math.max(budget, 0),
                segments.stream().mapToInt(ContextSegment::tokenCount).sum(),
                elapsedMs(retrieveStarted));
        log.info("retrieval done queryId={} recallMs={} rerankMs={} totalMs={}",
                queryId, recallMs, rerankMs, elapsedMs(retrieveStarted));
        return segments;
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
