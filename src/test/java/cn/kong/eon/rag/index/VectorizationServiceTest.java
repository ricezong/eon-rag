package cn.kong.eon.rag.index;

import cn.kong.eon.rag.common.exception.BizException;
import cn.kong.eon.rag.config.properties.KbEmbeddingProperties;
import cn.kong.eon.rag.repo.entity.ChildChunk;
import cn.kong.eon.rag.repo.entity.DocumentEntity;
import cn.kong.eon.rag.repo.mapper.ChildChunkMapper;
import cn.kong.eon.rag.repo.mapper.DocumentMapper;
import cn.kong.eon.rag.repo.mapper.ParentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 手动向量化状态机：入场状态限制、只算空向量、批处理、失败落 INDEX_FAILED。
 */
class VectorizationServiceTest {

    private DocumentMapper documents;
    private ParentChunkMapper parents;
    private ChildChunkMapper children;
    private EmbeddingModel embedding;
    private VectorizationService service;
    private UUID docId;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentMapper.class);
        parents = mock(ParentChunkMapper.class);
        children = mock(ChildChunkMapper.class);
        embedding = mock(EmbeddingModel.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new VectorizationService(documents, parents, children, embedding,
                new KbEmbeddingProperties(), tm, new SyncTaskExecutor());
        docId = UUID.randomUUID();
    }

    private DocumentEntity doc(String status) {
        DocumentEntity d = new DocumentEntity();
        d.setId(docId);
        d.setStatus(status);
        return d;
    }

    private ChildChunk child(int seq) {
        ChildChunk c = new ChildChunk();
        c.setId(UUID.randomUUID());
        c.setDocumentId(docId);
        c.setSeq(seq);
        c.setContent("内容" + seq);
        c.setTokenCount(10);
        return c;
    }

    @Test
    void embedsOnlyUnindexedAndCompletes() {
        when(documents.selectById(docId)).thenReturn(doc("CHUNKED"));
        when(documents.updateStatusIfCurrent(eq(docId), eq("CHUNKED"), eq("INDEXING"), any())).thenReturn(1);
        List<ChildChunk> pending = List.of(child(0), child(1), child(2));
        when(children.selectUnindexedByDocumentId(docId)).thenReturn(pending);
        when(embedding.embed(anyList())).thenReturn(List.of(
                new float[]{0.1f, 0.2f}, new float[]{0.1f, 0.2f}, new float[]{0.1f, 0.2f}));
        when(children.countUnindexedByDocumentId(docId)).thenReturn(0L);
        when(parents.countByDocumentId(docId)).thenReturn(2L);
        when(children.countByDocumentId(docId)).thenReturn(3L);

        service.start(docId);

        verify(embedding, times(1)).embed(anyList());
        verify(children, times(3)).updateEmbedding(any(), any());
        verify(documents).updateCountsAndStatus(docId, 2, 3, "COMPLETED");
    }

    @Test
    void embeddingFailureMarksIndexFailed() {
        when(documents.selectById(docId)).thenReturn(doc("CHUNKED"));
        when(documents.updateStatusIfCurrent(eq(docId), eq("CHUNKED"), eq("INDEXING"), any())).thenReturn(1);
        when(children.countByDocumentId(docId)).thenReturn(1L);
        when(children.selectUnindexedByDocumentId(docId)).thenReturn(List.of(child(0)));
        when(embedding.embed(anyList())).thenThrow(new IllegalStateException("boom"));

        service.start(docId);

        verify(children, never()).updateEmbedding(any(), any());
        verify(documents).updateStatus(eq(docId), eq("INDEX_FAILED"), any());
        verify(documents, never()).updateCountsAndStatus(any(), anyInt(), anyInt(), eq("COMPLETED"));
    }

    @Test
    void completedDocumentCannotReenter() {
        when(documents.selectById(docId)).thenReturn(doc("COMPLETED"));
        assertThrows(BizException.class, () -> service.start(docId));
        verify(embedding, never()).embed(anyList());
    }

    @Test
    void concurrentSecondClickIsRejected() {
        when(documents.selectById(docId)).thenReturn(doc("CHUNKED"));
        when(documents.updateStatusIfCurrent(eq(docId), eq("CHUNKED"), eq("INDEXING"), any())).thenReturn(0);
        assertThrows(BizException.class, () -> service.start(docId));
        verify(embedding, never()).embed(anyList());
    }
}
