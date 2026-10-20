package cn.kong.eon.rag.ingest;

import cn.kong.eon.rag.chunk.ChildChunker;
import cn.kong.eon.rag.chunk.ChunkDraft;
import cn.kong.eon.rag.chunk.ParentChunker;
import cn.kong.eon.rag.common.enums.DocumentStatus;
import cn.kong.eon.rag.common.enums.ParseMode;
import cn.kong.eon.rag.common.exception.BizException;
import cn.kong.eon.rag.common.exception.ErrorCode;
import cn.kong.eon.rag.config.properties.KbProperties;
import cn.kong.eon.rag.parse.Block;
import cn.kong.eon.rag.parse.ParseRouter;
import cn.kong.eon.rag.repo.entity.ChildChunk;
import cn.kong.eon.rag.repo.entity.DocumentEntity;
import cn.kong.eon.rag.repo.entity.ParentChunk;
import cn.kong.eon.rag.repo.mapper.ChildChunkMapper;
import cn.kong.eon.rag.repo.mapper.DocumentMapper;
import cn.kong.eon.rag.repo.mapper.ParentChunkMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 异步摄入流水线：解析 → 父块切分 → 子块切分 → 单事务落库。
 *
 * 上传阶段只做解析与分块，子块 embedding 为空，文档状态置为 CHUNKED；
 * 向量化由用户在前端确认切片后手动触发（见 VectorizationService）。
 */
@Service
public class IngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);

    private final ParseRouter parseRouter;
    private final ParentChunker parentChunker;
    private final ChildChunker childChunker;
    private final ContextPrefixGenerator prefixGenerator;
    private final DocumentMapper documentMapper;
    private final ParentChunkMapper parentChunkMapper;
    private final ChildChunkMapper childChunkMapper;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;
    private final KbProperties kbProperties;
    private final Path tempDir;
    private final TaskExecutor ingestExecutor;

    public IngestPipeline(ParseRouter parseRouter,
                          ParentChunker parentChunker,
                          ChildChunker childChunker,
                          ContextPrefixGenerator prefixGenerator,
                          DocumentMapper documentMapper,
                          ParentChunkMapper parentChunkMapper,
                          ChildChunkMapper childChunkMapper,
                          PlatformTransactionManager txManager,
                          ObjectMapper objectMapper,
                          KbProperties kbProperties,
                          @Qualifier("ingestExecutor") TaskExecutor ingestExecutor) {
        this.parseRouter = parseRouter;
        this.parentChunker = parentChunker;
        this.childChunker = childChunker;
        this.prefixGenerator = prefixGenerator;
        this.documentMapper = documentMapper;
        this.parentChunkMapper = parentChunkMapper;
        this.childChunkMapper = childChunkMapper;
        this.txTemplate = new TransactionTemplate(txManager);
        this.objectMapper = objectMapper;
        this.kbProperties = kbProperties;
        this.tempDir = Path.of(kbProperties.getIngest().getTempDir());
        this.ingestExecutor = ingestExecutor;
    }

    /**
     * 接收上传文件：流式转存自有临时目录，登记文档并提交异步任务，返回文档 ID。
     * MultipartFile 底层临时文件随 HTTP 请求返回被清理，必须先转存。
     */
    public UUID accept(String fileName, String fileType, long fileSize, InputStream source, String parseMode) {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(UUID.randomUUID());
        doc.setFileName(fileName);
        doc.setFileType(fileType);
        doc.setFileSize(fileSize);
        doc.setStatus(DocumentStatus.PROCESSING.name());
        doc.setParseMode(normalizeMode(parseMode));

        long startedAt = System.nanoTime();
        Path tempFile = relayToTempDir(doc.getId(), source);
        try {
            documentMapper.insert(doc);
            log.info("ingest stage=accept docId={} fileName={} fileType={} fileSize={} parseMode={} elapsedMs={}",
                    doc.getId(), fileName, fileType, fileSize, doc.getParseMode(), elapsedMs(startedAt));

            try {
                ingestExecutor.execute(() ->
                        runIngest(doc.getId(), tempFile, fileType, fileName, doc.getParseMode()));
            } catch (RuntimeException e) {
                log.error("ingest stage=rejected docId={}", doc.getId(), e);
                documentMapper.updateStatus(
                        doc.getId(), DocumentStatus.FAILED.name(), "摄入任务队列已满");
                throw e;
            }
            return doc.getId();
        } catch (RuntimeException e) {
            cleanupQuietly(tempFile);
            throw e;
        }
    }

    /** parseMode 归一：仅接受 AUTO/LOCAL/CLOUD，其余回落 AUTO */
    private static String normalizeMode(String parseMode) {
        if (parseMode == null || parseMode.isBlank()) {
            return ParseMode.AUTO.name();
        }
        String upper = parseMode.strip().toUpperCase(Locale.ROOT);
        return Set.of("AUTO", "LOCAL", "CLOUD").contains(upper) ? upper : ParseMode.AUTO.name();
    }

    private Path relayToTempDir(UUID docId, InputStream source) {
        try {
            Files.createDirectories(tempDir);
            Path file = tempDir.resolve(docId + ".upload");
            Files.copy(source, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 摄入任务：无论成功或异常，finally 中彻底清理自有临时文件 */
    private void runIngest(UUID docId, Path tempFile, String fileType, String fileName, String parseMode) {
        long startedAt = System.nanoTime();
        log.info("ingest stage=start docId={} fileName={} fileType={} parseMode={}",
                docId, fileName, fileType, parseMode);
        try {
            process(docId, tempFile, fileType, fileName, parseMode);
        } catch (BizException e) {
            log.warn("ingest stage=failed docId={} code={} message={} elapsedMs={}",
                    docId, e.getErrorCode(), e.getMessage(), elapsedMs(startedAt));
            documentMapper.updateStatus(docId, e.getErrorCode() == ErrorCode.NEEDS_OCR
                            ? DocumentStatus.NEEDS_OCR.name() : DocumentStatus.FAILED.name(),
                    e.getMessage());
        } catch (Exception e) {
            log.error("ingest stage=failed docId={} elapsedMs={}", docId, elapsedMs(startedAt), e);
            documentMapper.updateStatus(docId, DocumentStatus.FAILED.name(), e.getMessage());
        } finally {
            cleanupQuietly(tempFile);
        }
    }

    private void process(UUID docId, Path tempFile, String fileType, String fileName, String parseMode) {
        long processStarted = System.nanoTime();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(tempFile);
        } catch (IOException e) {
            throw new BizException(ErrorCode.PARSE_FAILED, "temp file read failed", e);
        }
        log.info("ingest stage=parse.start docId={} fileType={} parseMode={} bytes={}",
                docId, fileType, parseMode, bytes.length);

        // 1. 解析
        long parseStarted = System.nanoTime();
        List<Block> blocks = parseRouter.parse(bytes, fileType, parseMode, fileName);
        log.info("ingest stage=parse.done docId={} blocks={} elapsedMs={}",
                docId, blocks.size(), elapsedMs(parseStarted));

        // 2. 父子分块 + 预生成 UUID；file_name 统一写入父块 meta
        long parentStarted = System.nanoTime();
        List<ChunkDraft.Parent> parentDrafts = parentChunker.split(blocks);
        List<ParentChunk> parentEntities = new ArrayList<>(parentDrafts.size());
        IntSummaryStatistics parentTokens = new IntSummaryStatistics();
        int parentSeq = 0;
        for (ChunkDraft.Parent parent : parentDrafts) {
            ParentChunk entity = toParentEntity(docId, parentSeq++, fileName, parent);
            parentEntities.add(entity);
            parentTokens.accept(entity.getTokenCount());
        }
        if (parentEntities.isEmpty()) {
            throw new BizException(ErrorCode.PARSE_FAILED, "no chunks produced");
        }
        log.info("ingest stage=parent_chunk.done docId={} parents={} tokensMin={} tokensMax={} tokensAvg={} elapsedMs={}",
                docId, parentEntities.size(), parentTokens.getMin(), parentTokens.getMax(),
                average(parentTokens), elapsedMs(parentStarted));

        long childStarted = System.nanoTime();
        List<ChildChunk> childEntities = new ArrayList<>();
        IntSummaryStatistics childTokens = new IntSummaryStatistics();
        for (int i = 0; i < parentDrafts.size(); i++) {
            ParentChunk parent = parentEntities.get(i);
            long parentChildStarted = System.nanoTime();
            int seq = 0;
            List<ChunkDraft.Child> childDrafts = childChunker.split(parentDrafts.get(i));
            for (ChunkDraft.Child child : childDrafts) {
                ChildChunk entity = toChildEntity(docId, parent.getId(), seq++, child);
                childEntities.add(entity);
                childTokens.accept(entity.getTokenCount());
            }
            log.debug("ingest stage=child_chunk docId={} parentSeq={} children={} elapsedMs={}",
                    docId, parent.getSeq(), childDrafts.size(), elapsedMs(parentChildStarted));
        }
        if (childEntities.isEmpty()) {
            throw new BizException(ErrorCode.PARSE_FAILED, "no chunks produced");
        }
        log.info("ingest stage=child_chunk.done docId={} children={} tokensMin={} tokensMax={} tokensAvg={} elapsedMs={}",
                docId, childEntities.size(), childTokens.getMin(), childTokens.getMax(),
                average(childTokens), elapsedMs(childStarted));

        // 3. 上下文前缀增强（可选；不触发 embedding）
        long prefixStarted = System.nanoTime();
        int prefixed = enrichPrefixes(fileName, parentEntities, childEntities);
        log.info("ingest stage=prefix.done docId={} enabled={} prefixed={} elapsedMs={}",
                docId, kbProperties.getContextEnrichment().isEnabled(), prefixed, elapsedMs(prefixStarted));

        // 4. 单事务落库：embedding 为空，等待用户手动向量化
        long persistStarted = System.nanoTime();
        txTemplate.executeWithoutResult(status -> {
            parentChunkMapper.insertBatch(parentEntities);
            childChunkMapper.insertBatch(childEntities);
            documentMapper.updateCountsAndStatus(
                    docId, parentEntities.size(), childEntities.size(), DocumentStatus.CHUNKED.name());
        });
        log.info("ingest stage=persist.done docId={} parents={} children={} status=CHUNKED elapsedMs={}",
                docId, parentEntities.size(), childEntities.size(), elapsedMs(persistStarted));
        log.info("ingest stage=chunked docId={} parents={} children={} totalMs={}",
                docId, parentEntities.size(), childEntities.size(), elapsedMs(processStarted));
    }

    /** 为符合条件的子块生成背景前缀；生成失败置空继续（不阻断摄入） */
    private int enrichPrefixes(String fileName, List<ParentChunk> parents, List<ChildChunk> children) {
        if (!kbProperties.getContextEnrichment().isEnabled()) {
            return 0;
        }
        Map<UUID, String> titleByParent = new HashMap<>();
        for (ParentChunk parent : parents) {
            titleByParent.put(parent.getId(), parent.getTitle());
        }
        int prefixed = 0;
        for (ChildChunk child : children) {
            if (prefixGenerator.needsPrefix(titleByParent.get(child.getParentId()), child.getContent())) {
                String prefix = prefixGenerator.generate(fileName,
                        titleByParent.get(child.getParentId()), child.getContent());
                if (prefix != null && !prefix.isBlank()) {
                    child.setContextPrefix(prefix);
                    prefixed++;
                }
            }
        }
        return prefixed;
    }

    private ParentChunk toParentEntity(UUID docId, int seq, String fileName, ChunkDraft.Parent draft) {
        ParentChunk pe = new ParentChunk();
        pe.setId(UUID.randomUUID());
        pe.setDocumentId(docId);
        pe.setSeq(seq);
        pe.setTitle(draft.title());
        pe.setContent(draft.content());
        pe.setTokenCount(draft.tokenCount());
        pe.setMeta(toJson(mergeFileName(fileName, draft.meta())));
        return pe;
    }

    /** Block 契约：父块 meta 携带 file_name 供引用定位 */
    private static Map<String, String> mergeFileName(String fileName, Map<String, String> meta) {
        Map<String, String> merged = meta == null || meta.isEmpty()
                ? new LinkedHashMap<>() : new LinkedHashMap<>(meta);
        if (fileName != null && !fileName.isBlank()) {
            merged.put("file_name", fileName);
        }
        return merged;
    }

    private ChildChunk toChildEntity(UUID docId, UUID parentId, int seq, ChunkDraft.Child draft) {
        ChildChunk ce = new ChildChunk();
        ce.setId(UUID.randomUUID());
        ce.setParentId(parentId);
        ce.setDocumentId(docId);
        ce.setSeq(seq);
        ce.setContent(draft.content());
        ce.setTokenCount(draft.tokenCount());
        ce.setMeta(toJson(Map.of()));
        return ce;
    }

    private String toJson(Map<String, String> meta) {
        if (meta == null || meta.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static long average(IntSummaryStatistics statistics) {
        return statistics.getCount() == 0 ? 0 : Math.round(statistics.getAverage());
    }

    private void cleanupQuietly(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("ingest stage=cleanup.failed file={}", tempFile, e);
            }
        }
    }
}
