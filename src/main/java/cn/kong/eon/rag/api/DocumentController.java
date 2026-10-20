package cn.kong.eon.rag.api;

import cn.kong.eon.rag.common.api.ApiResponse;
import cn.kong.eon.rag.common.api.PageResult;
import cn.kong.eon.rag.common.exception.BizException;
import cn.kong.eon.rag.common.exception.ErrorCode;
import cn.kong.eon.rag.config.properties.KbParseProperties;
import cn.kong.eon.rag.ingest.IngestPipeline;
import cn.kong.eon.rag.repo.entity.DocumentEntity;
import cn.kong.eon.rag.repo.entity.DocumentKbBinding;
import cn.kong.eon.rag.repo.entity.ParentChunk;
import cn.kong.eon.rag.repo.mapper.DocumentMapper;
import cn.kong.eon.rag.repo.mapper.KnowledgeBaseMapper;
import cn.kong.eon.rag.repo.mapper.ParentChunkMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 文档接口：上传（异步摄入）/ 列表 / 详情 / 删除（级联清理由 FK 保证）。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    /** 支持上传的文件扩展名由解析路由决定：本地解析覆盖常见格式，云端模式支持 LlamaParse 的扩展格式。 */
    private final IngestPipeline ingestPipeline;
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ParentChunkMapper parentChunkMapper;
    private final KbParseProperties parseProperties;

    public DocumentController(IngestPipeline ingestPipeline,
                              DocumentMapper documentMapper,
                              KnowledgeBaseMapper kbMapper,
                              ParentChunkMapper parentChunkMapper,
                              KbParseProperties parseProperties) {
        this.ingestPipeline = ingestPipeline;
        this.documentMapper = documentMapper;
        this.kbMapper = kbMapper;
        this.parentChunkMapper = parentChunkMapper;
        this.parseProperties = parseProperties;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, UUID>>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "kbIds", required = false) List<UUID> kbIds,
            @RequestParam(value = "parseMode", required = false) String parseMode) {
        String fileName = file.getOriginalFilename();
        String fileType = extensionOf(fileName);
        if (file.getSize() > parseProperties.getMaxFileSize()) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE, file.getSize() + " bytes");
        }
        // MultipartFile 底层临时文件随请求返回被清理，须先转存
        UUID docId;
        try (InputStream in = file.getInputStream()) {
            docId = ingestPipeline.accept(fileName, fileType, file.getSize(), in, parseMode);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (kbIds != null && !kbIds.isEmpty()) {
            kbMapper.bindDocument(docId, kbIds);
        }
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(Map.of("documentId", docId)));
    }

    @GetMapping
    public ApiResponse<List<DocumentEntity>> list() {
        List<DocumentEntity> docs = documentMapper.selectAll();
        attachKbIds(docs);
        return ApiResponse.ok(docs);
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentEntity> get(@PathVariable UUID id) {
        DocumentEntity doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.DOC_NOT_FOUND, id.toString());
        }
        attachKbIds(List.of(doc));
        return ApiResponse.ok(doc);
    }

    /** 分页列出文档的父块（文档详情与引用浏览） */
    @GetMapping("/{id}/parent-chunks")
    public ApiResponse<PageResult<ParentChunk>> parentChunks(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (documentMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.DOC_NOT_FOUND, id.toString());
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        long total = parentChunkMapper.countByDocumentId(id);
        List<ParentChunk> items = parentChunkMapper.selectPageByDocumentId(
                id, safePage * safeSize, safeSize);
        return ApiResponse.ok(PageResult.of(items, total, safePage, safeSize));
    }

    /** 取父块原文（引用定位） */
    @GetMapping("/{id}/parent-chunks/{parentId}")
    public ApiResponse<ParentChunk> parentChunk(@PathVariable UUID id, @PathVariable UUID parentId) {
        ParentChunk parent = parentChunkMapper.selectById(parentId);
        if (parent == null || !id.equals(parent.getDocumentId())) {
            throw new BizException(ErrorCode.PARENT_NOT_FOUND, parentId.toString());
        }
        return ApiResponse.ok(parent);
    }

    /** 删除主表记录，父子块经 ON DELETE CASCADE 自动级联清理 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        if (documentMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.DOC_NOT_FOUND, id.toString());
        }
        documentMapper.deleteById(id);
        return ApiResponse.ok();
    }

    private String extensionOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /** 批量回填文档所属知识库，避免列表逐条查询 */
    private void attachKbIds(List<DocumentEntity> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        Map<UUID, List<UUID>> kbIdsByDoc = new HashMap<>();
        for (DocumentKbBinding binding : kbMapper.selectAllBindings()) {
            kbIdsByDoc.computeIfAbsent(binding.getDocumentId(), key -> new ArrayList<>())
                    .add(binding.getKnowledgeBaseId());
        }
        for (DocumentEntity doc : docs) {
            doc.setKbIds(kbIdsByDoc.getOrDefault(doc.getId(), List.of()));
        }
    }
}
