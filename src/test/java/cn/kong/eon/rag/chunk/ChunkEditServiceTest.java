package cn.kong.eon.rag.chunk;

import cn.kong.eon.rag.api.dto.ChunkEditRequests;
import cn.kong.eon.rag.common.exception.BizException;
import cn.kong.eon.rag.config.properties.KbChunkProperties;
import cn.kong.eon.rag.config.properties.KbEmbeddingProperties;
import cn.kong.eon.rag.repo.entity.ChildChunk;
import cn.kong.eon.rag.repo.entity.DocumentEntity;
import cn.kong.eon.rag.repo.entity.ParentChunk;
import cn.kong.eon.rag.repo.mapper.ChildChunkMapper;
import cn.kong.eon.rag.repo.mapper.DocumentMapper;
import cn.kong.eon.rag.repo.mapper.ParentChunkMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 切片编辑语义：合并去滑窗重叠、连续性校验、Token 上限、乐观锁冲突。
 */
class ChunkEditServiceTest {

    private static final TokenCounter CHARS = new TokenCounter() {
        @Override
        public int count(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public String tail(String text, int maxTokens) {
            return text.length() <= maxTokens ? text : text.substring(text.length() - maxTokens);
        }

        @Override
        public String head(String text, int maxTokens) {
            return text.length() <= maxTokens ? text : text.substring(0, maxTokens);
        }
    };

    private DocumentMapper documents;
    private ParentChunkMapper parents;
    private ChildChunkMapper children;
    private ChunkEditService service;
    private UUID docId;
    private UUID parentId;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentMapper.class);
        parents = mock(ParentChunkMapper.class);
        children = mock(ChildChunkMapper.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ChunkEditService(documents, parents, children, mock(EmbeddingModel.class),
                CHARS, mock(ChildChunker.class), new KbChunkProperties(),
                new KbEmbeddingProperties(), new ObjectMapper(), tm);
        docId = UUID.randomUUID();
        parentId = UUID.randomUUID();

        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setStatus("CHUNKED");
        when(documents.selectById(docId)).thenReturn(doc);
        when(documents.touchStatusIfCurrent(any(), any())).thenReturn(1);

        ParentChunk parent = new ParentChunk();
        parent.setId(parentId);
        parent.setDocumentId(docId);
        parent.setContent("旧父块");
        when(parents.selectById(parentId)).thenReturn(parent);
    }

    private ChildChunk child(UUID id, int seq, String content) {
        ChildChunk c = new ChildChunk();
        c.setId(id);
        c.setParentId(parentId);
        c.setDocumentId(docId);
        c.setSeq(seq);
        c.setContent(content);
        c.setTokenCount(content.length());
        c.setMeta("{}");
        return c;
    }

    @Test
    void mergeDeduplicatesSlidingOverlap() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ChildChunk ca = child(a, 0, "甲乙丙丁");
        ChildChunk cb = child(b, 1, "丙丁戊己");
        when(children.selectById(a)).thenReturn(ca);
        when(children.selectById(b)).thenReturn(cb);
        when(children.updateContentIfUnchanged(any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(1);
        when(children.selectByParentId(parentId)).thenReturn(List.of(ca, cb));

        service.mergeChildren(docId, parentId, new ChunkEditRequests.Merge(List.of(a, b)));

        ArgumentCaptor<String> merged = ArgumentCaptor.forClass(String.class);
        verify(children).updateContentIfUnchanged(any(), any(), merged.capture(),
                any(), any(), anyInt(), any());
        assertEquals("甲乙丙丁戊己", merged.getValue());
        verify(children).deleteById(b);
    }

    @Test
    void mergeRejectsNonConsecutiveChildren() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(children.selectById(a)).thenReturn(child(a, 0, "甲"));
        when(children.selectById(b)).thenReturn(child(b, 3, "乙"));
        BizException e = assertThrows(BizException.class,
                () -> service.mergeChildren(docId, parentId, new ChunkEditRequests.Merge(List.of(a, b))));
        assertTrue(e.getMessage().contains("连续"));
    }

    @Test
    void updateChildOverTokenLimitRejected() {
        UUID a = UUID.randomUUID();
        when(children.selectById(a)).thenReturn(child(a, 0, "甲"));
        BizException e = assertThrows(BizException.class,
                () -> service.updateChild(docId, parentId, a,
                        new ChunkEditRequests.ChildUpdate("x".repeat(321))));
        assertTrue(e.getMessage().contains("320"));
    }

    @Test
    void updateChildConflictWhenContentChangedConcurrently() {
        UUID a = UUID.randomUUID();
        when(children.selectById(a)).thenReturn(child(a, 0, "甲"));
        when(children.updateContentIfUnchanged(any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(0);
        assertThrows(BizException.class,
                () -> service.updateChild(docId, parentId, a, new ChunkEditRequests.ChildUpdate("乙")));
    }
}
