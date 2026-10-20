package cn.kong.eon.rag.index;

import cn.kong.eon.rag.common.enums.DocumentStatus;
import cn.kong.eon.rag.common.exception.BizException;
import cn.kong.eon.rag.common.exception.ErrorCode;
import cn.kong.eon.rag.common.util.EmbeddingSupport;
import cn.kong.eon.rag.config.properties.KbEmbeddingProperties;
import cn.kong.eon.rag.repo.entity.ChildChunk;
import cn.kong.eon.rag.repo.entity.DocumentEntity;
import cn.kong.eon.rag.repo.mapper.ChildChunkMapper;
import cn.kong.eon.rag.repo.mapper.DocumentMapper;
import cn.kong.eon.rag.repo.mapper.ParentChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 手动向量化：把文档中 embedding 为空的子块逐批向量化并写回。
 *
 * 状态机：CHUNKED / INDEX_FAILED → INDEXING → COMPLETED / INDEX_FAILED。
 * 只处理空向量，支持失败后重试；已完成的批次不会重复计算。
 */
@Service
public class VectorizationService {

    private static final Logger log = LoggerFactory.getLogger(VectorizationService.class);

    private final DocumentMapper documentMapper;
    private final ParentChunkMapper parentChunkMapper;
    private final ChildChunkMapper childChunkMapper;
    private final EmbeddingModel embeddingModel;
    private final int batchSize;
    private final TransactionTemplate txTemplate;
    private final TaskExecutor indexExecutor;

    public VectorizationService(DocumentMapper documentMapper,
                                ParentChunkMapper parentChunkMapper,
                                ChildChunkMapper childChunkMapper,
                                EmbeddingModel embeddingModel,
                                KbEmbeddingProperties embeddingProperties,
                                PlatformTransactionManager txManager,
                                @Qualifier("ingestExecutor") TaskExecutor indexExecutor) {
        this.documentMapper = documentMapper;
        this.parentChunkMapper = parentChunkMapper;
        this.childChunkMapper = childChunkMapper;
        this.embeddingModel = embeddingModel;
        this.batchSize = Math.max(1, embeddingProperties.getBatchSize());
        this.txTemplate = new TransactionTemplate(txManager);
        this.indexExecutor = indexExecutor;
    }

    /**
     * 异步启动文档向量化。仅 CHUNKED / INDEX_FAILED 可以进入 INDEXING。
     *
     * @return 提交后的实际状态；线程池回落到调用线程时可能已是 COMPLETED / INDEX_FAILED
     */
    public DocumentStatus start(UUID documentId) {
        DocumentEntity document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException(ErrorCode.DOC_NOT_FOUND, documentId.toString());
        }
        String previousStatus = document.getStatus();
        if (!DocumentStatus.CHUNKED.name().equals(previousStatus)
                && !DocumentStatus.INDEX_FAILED.name().equals(previousStatus)) {
            throw new BizException(ErrorCode.INDEX_CONFLICT, previousStatus);
        }
        long childCount = childChunkMapper.countByDocumentId(documentId);
        if (childCount == 0) {
            throw new BizException(ErrorCode.INVALID_CHUNK_OPERATION, "文档没有可向量化的子块");
        }

        int changed = documentMapper.updateStatusIfCurrent(
                documentId, previousStatus, DocumentStatus.INDEXING.name(), null);
        if (changed == 0) {
            throw new BizException(ErrorCode.INDEX_CONFLICT, previousStatus);
        }

        log.info("index stage=queued docId={} previousStatus={} childCount={}",
                documentId, previousStatus, childCount);
        try {
            indexExecutor.execute(() -> run(documentId));
        } catch (RuntimeException e) {
            log.error("index stage=rejected docId={}", documentId, e);
            documentMapper.updateStatus(documentId, DocumentStatus.INDEX_FAILED.name(),
                    "向量化任务队列已满，请稍后重试");
            throw new BizException(ErrorCode.INDEX_CONFLICT, "向量化任务队列已满，请稍后重试");
        }
        DocumentEntity current = documentMapper.selectById(documentId);
        return current == null ? DocumentStatus.INDEXING : DocumentStatus.valueOf(current.getStatus());
    }

    private void run(UUID documentId) {
        long startedAt = System.nanoTime();
        try {
            List<ChildChunk> pending = childChunkMapper.selectUnindexedByDocumentId(documentId);
            int total = pending.size();
            int batches = total == 0 ? 0 : (total + batchSize - 1) / batchSize;
            log.info("index stage=start docId={} chunks={} batches={} batchSize={}",
                    documentId, total, batches, batchSize);

            for (int offset = 0; offset < total; offset += batchSize) {
                int to = Math.min(offset + batchSize, total);
                List<ChildChunk> batch = pending.subList(offset, to);
                int batchNo = offset / batchSize + 1;
                long batchStarted = System.nanoTime();
                List<String> texts = batch.stream()
                        .map(child -> EmbeddingSupport.input(child.getContextPrefix(), child.getContent()))
                        .toList();
                List<float[]> vectors = embeddingModel.embed(texts);
                if (vectors == null || vectors.size() != batch.size()) {
                    throw new BizException(ErrorCode.EMBEDDING_FAILED,
                            "embedding size mismatch: expected %d got %d"
                                    .formatted(batch.size(), vectors == null ? 0 : vectors.size()));
                }

                txTemplate.executeWithoutResult(status -> {
                    for (int i = 0; i < batch.size(); i++) {
                        childChunkMapper.updateEmbedding(
                                batch.get(i).getId(), EmbeddingSupport.toPgVector(vectors.get(i)));
                    }
                });
                log.info("index stage=batch docId={} batch={}/{} chunks={} tokens={} elapsedMs={}",
                        documentId, batchNo, batches, batch.size(),
                        batch.stream().mapToInt(child -> child.getTokenCount() == null ? 0 : child.getTokenCount()).sum(),
                        elapsedMs(batchStarted));
            }

            long remaining = childChunkMapper.countUnindexedByDocumentId(documentId);
            if (remaining > 0) {
                throw new BizException(ErrorCode.EMBEDDING_FAILED,
                        "向量化后仍有 " + remaining + " 个子块没有向量");
            }
            int parentCount = (int) parentChunkMapper.countByDocumentId(documentId);
            int childCount = (int) childChunkMapper.countByDocumentId(documentId);
            documentMapper.updateCountsAndStatus(
                    documentId, parentCount, childCount, DocumentStatus.COMPLETED.name());
            log.info("index stage=done docId={} embedded={} parents={} children={} elapsedMs={}",
                    documentId, total, parentCount, childCount, elapsedMs(startedAt));
        } catch (Exception e) {
            log.error("index stage=failed docId={} elapsedMs={}", documentId, elapsedMs(startedAt), e);
            documentMapper.updateStatus(documentId, DocumentStatus.INDEX_FAILED.name(),
                    e.getMessage() == null ? "向量化失败" : e.getMessage());
        }
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
