package cn.kong.eon.rag.chunk;

import cn.kong.eon.rag.api.dto.ChunkEditRequests;
import cn.kong.eon.rag.common.enums.DocumentStatus;
import cn.kong.eon.rag.common.exception.BizException;
import cn.kong.eon.rag.common.exception.ErrorCode;
import cn.kong.eon.rag.config.properties.KbChunkProperties;
import cn.kong.eon.rag.config.properties.KbEmbeddingProperties;
import cn.kong.eon.rag.common.util.EmbeddingSupport;
import cn.kong.eon.rag.repo.entity.ChildChunk;
import cn.kong.eon.rag.repo.entity.DocumentEntity;
import cn.kong.eon.rag.repo.entity.ParentChunk;
import cn.kong.eon.rag.repo.mapper.ChildChunkMapper;
import cn.kong.eon.rag.repo.mapper.DocumentMapper;
import cn.kong.eon.rag.repo.mapper.ParentChunkMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 父子块编辑：父块编辑/删除，子块编辑/删除/连续合并/光标拆分。
 *
 * 语义（对齐 Dify 父子分段）：
 * - 子块只影响检索，编辑不改变父块；
 * - 父块编辑默认按新正文重建子块，也可保留原子块；
 * - 删除最后一个子块时保留空父块；
 * - CHUNKED / INDEX_FAILED 编辑只保存文本，COMPLETED 编辑自动重嵌受影响子块。
 */
@Service
public class ChunkEditService {

    private static final Logger log = LoggerFactory.getLogger(ChunkEditService.class);

    private static final String EMPTY_META = "{}";
    private static final int SPLIT_TEMP_SEQ = -1_000_000;

    private final DocumentMapper documentMapper;
    private final ParentChunkMapper parentChunkMapper;
    private final ChildChunkMapper childChunkMapper;
    private final EmbeddingModel embeddingModel;
    private final TokenCounter tokenCounter;
    private final ChildChunker childChunker;
    private final KbChunkProperties chunkProperties;
    private final KbEmbeddingProperties embeddingProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ChunkEditService(DocumentMapper documentMapper,
                            ParentChunkMapper parentChunkMapper,
                            ChildChunkMapper childChunkMapper,
                            EmbeddingModel embeddingModel,
                            TokenCounter tokenCounter,
                            ChildChunker childChunker,
                            KbChunkProperties chunkProperties,
                            KbEmbeddingProperties embeddingProperties,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.documentMapper = documentMapper;
        this.parentChunkMapper = parentChunkMapper;
        this.childChunkMapper = childChunkMapper;
        this.embeddingModel = embeddingModel;
        this.tokenCounter = tokenCounter;
        this.childChunker = childChunker;
        this.chunkProperties = chunkProperties;
        this.embeddingProperties = embeddingProperties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<ChildChunk> listChildren(UUID documentId, UUID parentId) {
        requireDocument(documentId);
        requireParent(documentId, parentId);
        return childChunkMapper.selectByParentId(parentId);
    }

    public ParentChunk updateParent(UUID documentId, UUID parentId,
                                    ChunkEditRequests.ParentUpdate request) {
        DocumentEntity document = requireEditableDocument(documentId);
        ParentChunk parent = requireParent(documentId, parentId);
        boolean autoEmbed = autoEmbed(document);

        String content = request == null ? "" : stripToEmpty(request.content());
        if (content.isEmpty()) {
            throw invalid("父块内容不能为空");
        }
        int parentTokens = tokenCounter.count(content);
        int parentMax = chunkProperties.getParent().getMaxTokens();
        if (parentTokens > parentMax) {
            throw tokenLimit("父块", parentTokens, parentMax);
        }

        String title = request.title() == null ? null : request.title().strip();
        boolean rebuildChildren = request.rebuildChildren() == null || request.rebuildChildren();

        if (!rebuildChildren) {
            log.info("chunk.edit stage=parent.keep docId={} parentId={} tokens={}",
                    documentId, parentId, parentTokens);
            transactionTemplate.executeWithoutResult(status -> {
                lockEditableStatus(documentId, document.getStatus());
                int updated = parentChunkMapper.updateContentIfUnchanged(
                        parentId, parent.getContent(), title, content, parentTokens);
                if (updated == 0) {
                    throw conflict("父块");
                }
                refreshCounts(documentId);
            });
            return parentChunkMapper.selectById(parentId);
        }

        List<ChunkDraft.Child> drafts = childChunker.split(new ChunkDraft.Parent(
                title, kindOf(parent), content, parentTokens, parseMeta(parent.getMeta())));
        if (drafts.isEmpty()) {
            throw invalid("重建子块失败：新父块内容未产出任何子块");
        }
        validateChildDrafts(drafts);

        List<String> vectors = autoEmbed
                ? embedAll(drafts.stream()
                        .map(draft -> EmbeddingSupport.input(null, draft.content()))
                        .toList())
                : null;
        List<ChildChunk> rebuilt = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            ChunkDraft.Child draft = drafts.get(i);
            ChildChunk child = new ChildChunk();
            child.setId(UUID.randomUUID());
            child.setParentId(parentId);
            child.setDocumentId(documentId);
            child.setSeq(i);
            child.setContent(draft.content());
            child.setContextPrefix(null);
            child.setEmbedding(vectors == null ? null : vectors.get(i));
            child.setTokenCount(draft.tokenCount());
            child.setMeta(EMPTY_META);
            rebuilt.add(child);
        }

        log.info("chunk.edit stage=parent.rebuild docId={} parentId={} autoEmbed={} children={}",
                documentId, parentId, autoEmbed, rebuilt.size());
        transactionTemplate.executeWithoutResult(status -> {
            lockEditableStatus(documentId, document.getStatus());
            int updated = parentChunkMapper.updateContentIfUnchanged(
                    parentId, parent.getContent(), title, content, parentTokens);
            if (updated == 0) {
                throw conflict("父块");
            }
            childChunkMapper.deleteByParentId(parentId);
            childChunkMapper.insertBatch(rebuilt);
            refreshCounts(documentId);
        });
        return parentChunkMapper.selectById(parentId);
    }

    public void deleteParent(UUID documentId, UUID parentId) {
        DocumentEntity document = requireEditableDocument(documentId);
        requireParent(documentId, parentId);
        transactionTemplate.executeWithoutResult(status -> {
            lockEditableStatus(documentId, document.getStatus());
            parentChunkMapper.deleteById(parentId);
            renumberParents(documentId);
            refreshCounts(documentId);
        });
    }

    public ChildChunk updateChild(UUID documentId, UUID parentId, UUID childId,
                                  ChunkEditRequests.ChildUpdate request) {
        DocumentEntity document = requireEditableDocument(documentId);
        ChildChunk child = requireChild(documentId, parentId, childId);

        String content = request == null ? "" : stripToEmpty(request.content());
        if (content.isEmpty()) {
            throw invalid("子块内容不能为空");
        }
        int tokens = tokenCounter.count(content);
        int childMax = chunkProperties.getChild().getMaxTokens();
        if (tokens > childMax) {
            throw tokenLimit("子块", tokens, childMax);
        }

        boolean autoEmbed = autoEmbed(document);
        String embedding = autoEmbed ? embedOne(child.getContextPrefix(), content) : null;
        log.info("chunk.edit stage=child docId={} childId={} autoEmbed={} tokens={}",
                documentId, childId, autoEmbed, tokens);
        transactionTemplate.executeWithoutResult(status -> {
            lockEditableStatus(documentId, document.getStatus());
            int updated = childChunkMapper.updateContentIfUnchanged(
                    childId, child.getContent(), content, child.getContextPrefix(),
                    embedding, tokens, child.getMeta());
            if (updated == 0) {
                throw conflict("子块");
            }
            refreshCounts(documentId);
        });
        return childChunkMapper.selectById(childId);
    }

    public void deleteChild(UUID documentId, UUID parentId, UUID childId) {
        DocumentEntity document = requireEditableDocument(documentId);
        requireChild(documentId, parentId, childId);
        transactionTemplate.executeWithoutResult(status -> {
            lockEditableStatus(documentId, document.getStatus());
            childChunkMapper.deleteById(childId);
            renumberChildren(parentId);
            refreshCounts(documentId);
        });
    }

    public ChildChunk mergeChildren(UUID documentId, UUID parentId,
                                    ChunkEditRequests.Merge request) {
        DocumentEntity document = requireEditableDocument(documentId);
        requireParent(documentId, parentId);

        List<UUID> requested = request == null ? List.of() : request.childIds();
        if (requested == null || requested.size() < 2) {
            throw invalid("至少选择两个子块进行合并");
        }
        List<UUID> distinct = new ArrayList<>(new LinkedHashSet<>(requested));
        if (distinct.size() != requested.size()) {
            throw invalid("待合并子块不能重复");
        }

        List<ChildChunk> selected = new ArrayList<>(distinct.size());
        for (UUID childId : distinct) {
            selected.add(requireChild(documentId, parentId, childId));
        }
        selected.sort(Comparator.comparingInt(ChildChunk::getSeq));
        for (int i = 1; i < selected.size(); i++) {
            if (selected.get(i).getSeq() != selected.get(i - 1).getSeq() + 1) {
                throw invalid("只能合并同一父块内连续的子块");
            }
        }

        String mergedContent = mergeContents(selected);
        int tokens = tokenCounter.count(mergedContent);
        int mergeMax = chunkProperties.getChild().getMergeMaxTokens();
        if (tokens > mergeMax) {
            throw tokenLimit("合并后的子块", tokens, mergeMax);
        }

        ChildChunk first = selected.get(0);
        String prefix = first.getContextPrefix();
        boolean autoEmbed = autoEmbed(document);
        String embedding = autoEmbed ? embedOne(prefix, mergedContent) : null;
        log.info("chunk.edit stage=merge docId={} parentId={} merged={} autoEmbed={} tokens={}",
                documentId, parentId, selected.size(), autoEmbed, tokens);
        transactionTemplate.executeWithoutResult(status -> {
            lockEditableStatus(documentId, document.getStatus());
            int updated = childChunkMapper.updateContentIfUnchanged(
                    first.getId(), first.getContent(), mergedContent, prefix,
                    embedding, tokens, first.getMeta());
            if (updated == 0) {
                throw conflict("子块");
            }
            for (int i = 1; i < selected.size(); i++) {
                childChunkMapper.deleteById(selected.get(i).getId());
            }
            renumberChildren(parentId);
            refreshCounts(documentId);
        });
        return childChunkMapper.selectById(first.getId());
    }

    public List<ChildChunk> splitChild(UUID documentId, UUID parentId, UUID childId,
                                       ChunkEditRequests.Split request) {
        DocumentEntity document = requireEditableDocument(documentId);
        ChildChunk child = requireChild(documentId, parentId, childId);
        String content = child.getContent();
        Integer offset = request == null ? null : request.offset();
        if (offset == null || offset <= 0 || offset >= content.length()) {
            throw invalid("拆分位置必须位于子块内容中间");
        }

        String left = content.substring(0, offset).strip();
        String right = content.substring(offset).strip();
        if (left.isEmpty() || right.isEmpty()) {
            throw invalid("拆分后的两段内容都不能为空");
        }

        int childMax = chunkProperties.getChild().getMaxTokens();
        int leftTokens = tokenCounter.count(left);
        int rightTokens = tokenCounter.count(right);
        if (leftTokens > childMax) {
            throw tokenLimit("拆分后的前段", leftTokens, childMax);
        }
        if (rightTokens > childMax) {
            throw tokenLimit("拆分后的后段", rightTokens, childMax);
        }

        String prefix = child.getContextPrefix();
        boolean autoEmbed = autoEmbed(document);
        List<String> vectors = autoEmbed
                ? embedAll(List.of(
                        EmbeddingSupport.input(prefix, left),
                        EmbeddingSupport.input(prefix, right)))
                : null;

        UUID secondId = UUID.randomUUID();
        ChildChunk second = new ChildChunk();
        second.setId(secondId);
        second.setParentId(parentId);
        second.setDocumentId(documentId);
        second.setSeq(SPLIT_TEMP_SEQ);
        second.setContent(right);
        second.setContextPrefix(prefix);
        second.setEmbedding(vectors == null ? null : vectors.get(1));
        second.setTokenCount(rightTokens);
        second.setMeta(child.getMeta() == null ? EMPTY_META : child.getMeta());

        List<UUID> ordered = new ArrayList<>();
        for (ChildChunk item : childChunkMapper.selectByParentId(parentId)) {
            ordered.add(item.getId());
        }
        int index = ordered.indexOf(childId);
        if (index < 0) {
            throw new BizException(ErrorCode.CHUNK_NOT_FOUND, childId.toString());
        }
        ordered.add(index + 1, secondId);

        log.info("chunk.edit stage=split docId={} childId={} autoEmbed={} leftTokens={} rightTokens={}",
                documentId, childId, autoEmbed, leftTokens, rightTokens);
        transactionTemplate.executeWithoutResult(status -> {
            lockEditableStatus(documentId, document.getStatus());
            int updated = childChunkMapper.updateContentIfUnchanged(
                    childId, child.getContent(), left, prefix,
                    vectors == null ? null : vectors.get(0), leftTokens, child.getMeta());
            if (updated == 0) {
                throw conflict("子块");
            }
            childChunkMapper.insert(second);
            renumberChildren(ordered);
            refreshCounts(documentId);
        });
        return List.of(childChunkMapper.selectById(childId), childChunkMapper.selectById(secondId));
    }

    /* ---------- 校验与归属 ---------- */

    private DocumentEntity requireDocument(UUID documentId) {
        DocumentEntity document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException(ErrorCode.DOC_NOT_FOUND, documentId.toString());
        }
        return document;
    }

    private DocumentEntity requireEditableDocument(UUID documentId) {
        DocumentEntity document = requireDocument(documentId);
        String status = document.getStatus();
        if (!DocumentStatus.CHUNKED.name().equals(status)
                && !DocumentStatus.INDEX_FAILED.name().equals(status)
                && !DocumentStatus.COMPLETED.name().equals(status)) {
            throw new BizException(ErrorCode.DOC_NOT_EDITABLE, status);
        }
        return document;
    }

    /** COMPLETED 文档编辑后自动重嵌；待向量化/索引失败文档只保存文本 */
    private boolean autoEmbed(DocumentEntity document) {
        return DocumentStatus.COMPLETED.name().equals(document.getStatus());
    }

    /** 在编辑事务内按读取到的状态触发行更新；状态已被索引任务改变时回滚 */
    private void lockEditableStatus(UUID documentId, String expectedStatus) {
        if (documentMapper.touchStatusIfCurrent(documentId, expectedStatus) == 0) {
            throw new BizException(ErrorCode.DOC_NOT_EDITABLE, expectedStatus);
        }
    }

    private ParentChunk requireParent(UUID documentId, UUID parentId) {
        ParentChunk parent = parentChunkMapper.selectById(parentId);
        if (parent == null || !documentId.equals(parent.getDocumentId())) {
            throw new BizException(ErrorCode.CHUNK_NOT_FOUND, parentId.toString());
        }
        return parent;
    }

    private ChildChunk requireChild(UUID documentId, UUID parentId, UUID childId) {
        ChildChunk child = childChunkMapper.selectById(childId);
        if (child == null
                || !parentId.equals(child.getParentId())
                || !documentId.equals(child.getDocumentId())) {
            throw new BizException(ErrorCode.CHUNK_NOT_FOUND, childId.toString());
        }
        return child;
    }

    private void validateChildDrafts(List<ChunkDraft.Child> drafts) {
        int childMax = chunkProperties.getChild().getMaxTokens();
        for (ChunkDraft.Child draft : drafts) {
            if (draft.tokenCount() > childMax) {
                throw tokenLimit("重建后的子块", draft.tokenCount(), childMax);
            }
        }
    }

    /* ---------- 文本处理 ---------- */

    private String mergeContents(List<ChildChunk> children) {
        StringBuilder merged = new StringBuilder(children.get(0).getContent().strip());
        for (int i = 1; i < children.size(); i++) {
            String next = children.get(i).getContent().strip();
            int overlap = overlapLength(merged.toString(), next);
            if (overlap > 0) {
                merged.append(next.substring(overlap));
            } else {
                if (!merged.isEmpty()) {
                    merged.append("\n\n");
                }
                merged.append(next);
            }
        }
        return merged.toString().strip();
    }

    /** 合并时去除相邻子块的滑窗重叠，避免重复文本 */
    private int overlapLength(String previous, String next) {
        int max = Math.min(previous.length(), next.length());
        for (int length = max; length > 0; length--) {
            if (previous.regionMatches(previous.length() - length, next, 0, length)) {
                return length;
            }
        }
        return 0;
    }

    private String stripToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    /* ---------- 向量 ---------- */

    private String embedOne(String contextPrefix, String content) {
        return embedAll(List.of(EmbeddingSupport.input(contextPrefix, content))).get(0);
    }

    private List<String> embedAll(List<String> texts) {
        List<String> vectors = new ArrayList<>(texts.size());
        int batchSize = Math.max(1, embeddingProperties.getBatchSize());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(from + batchSize, texts.size());
            List<String> batch = texts.subList(from, to);
            List<float[]> embedded;
            try {
                embedded = embeddingModel.embed(batch);
            } catch (Exception e) {
                throw new BizException(ErrorCode.EMBEDDING_FAILED, e.getMessage(), e);
            }
            if (embedded == null || embedded.size() != batch.size()) {
                throw new BizException(ErrorCode.EMBEDDING_FAILED, "向量化返回数量与请求不一致");
            }
            for (float[] vector : embedded) {
                vectors.add(EmbeddingSupport.toPgVector(vector));
            }
        }
        return vectors;
    }

    /* ---------- 顺序与计数 ---------- */

    private void renumberParents(UUID documentId) {
        List<UUID> ordered = new ArrayList<>();
        for (ParentChunk parent : parentChunkMapper.selectByDocumentId(documentId)) {
            ordered.add(parent.getId());
        }
        for (int i = 0; i < ordered.size(); i++) {
            parentChunkMapper.updateSeq(ordered.get(i), -(i + 1));
        }
        for (int i = 0; i < ordered.size(); i++) {
            parentChunkMapper.updateSeq(ordered.get(i), i);
        }
    }

    private void renumberChildren(UUID parentId) {
        List<UUID> ordered = new ArrayList<>();
        for (ChildChunk child : childChunkMapper.selectByParentId(parentId)) {
            ordered.add(child.getId());
        }
        renumberChildren(ordered);
    }

    private void renumberChildren(List<UUID> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            childChunkMapper.updateSeq(ordered.get(i), -(i + 1));
        }
        for (int i = 0; i < ordered.size(); i++) {
            childChunkMapper.updateSeq(ordered.get(i), i);
        }
    }

    private void refreshCounts(UUID documentId) {
        int parentCount = (int) parentChunkMapper.countByDocumentId(documentId);
        int childCount = (int) childChunkMapper.countByDocumentId(documentId);
        documentMapper.updateCountsOnly(documentId, parentCount, childCount);
    }

    /* ---------- 父块类型 ---------- */

    private ChunkDraft.BlockKind kindOf(ParentChunk parent) {
        try {
            JsonNode meta = objectMapper.readTree(parent.getMeta());
            if (meta != null && meta.hasNonNull("slide_number")) {
                return ChunkDraft.BlockKind.SLIDE;
            }
            if (meta != null && (meta.hasNonNull("sheet_name") || meta.hasNonNull("rows"))) {
                return ChunkDraft.BlockKind.TABLE;
            }
        } catch (Exception ignored) {
            // 元数据损坏时按普通文本处理
        }
        return ChunkDraft.BlockKind.TEXT;
    }

    private Map<String, String> parseMeta(String meta) {
        if (meta == null || meta.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(meta, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /* ---------- 错误 ---------- */

    private BizException invalid(String detail) {
        return new BizException(ErrorCode.INVALID_CHUNK_OPERATION, detail);
    }

    private BizException conflict(String target) {
        return new BizException(ErrorCode.CHUNK_CONFLICT,
                target + "内容已被其他操作修改，请刷新后重试");
    }

    private BizException tokenLimit(String target, int actual, int limit) {
        return new BizException(ErrorCode.TOKEN_LIMIT_EXCEEDED,
                target + "为 " + actual + " Token，超过上限 " + limit);
    }
}
