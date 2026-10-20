package cn.kong.eon.rag.api;

import cn.kong.eon.rag.common.api.ApiResponse;
import cn.kong.eon.rag.common.exception.BizException;
import cn.kong.eon.rag.common.exception.ErrorCode;
import cn.kong.eon.rag.repo.entity.DocumentEntity;
import cn.kong.eon.rag.repo.entity.KnowledgeBase;
import cn.kong.eon.rag.repo.mapper.DocumentMapper;
import cn.kong.eon.rag.repo.mapper.KnowledgeBaseMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 知识库分类接口（CRUD + 文档绑定）。
 */
@RestController
@RequestMapping("/api/kbs")
public class KbController {

    private final KnowledgeBaseMapper kbMapper;
    private final DocumentMapper documentMapper;

    public KbController(KnowledgeBaseMapper kbMapper, DocumentMapper documentMapper) {
        this.kbMapper = kbMapper;
        this.documentMapper = documentMapper;
    }

    public record KbCreateRequest(String name, String description, String color) {
    }

    @PostMapping
    public ApiResponse<KnowledgeBase> create(@RequestBody KbCreateRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException(ErrorCode.INTERNAL, "name required");
        }
        if (kbMapper.existsByName(req.name())) {
            throw new BizException(ErrorCode.KB_NAME_EXISTS, req.name());
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(UUID.randomUUID());
        kb.setName(req.name());
        kb.setDescription(req.description() == null ? "" : req.description());
        kb.setColor(req.color() == null ? "#3b82f6" : req.color());
        kbMapper.insert(kb);
        return ApiResponse.ok(kbMapper.selectById(kb.getId()));
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBase>> list() {
        return ApiResponse.ok(kbMapper.selectAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBase> get(@PathVariable UUID id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new BizException(ErrorCode.KB_NOT_FOUND, id.toString());
        }
        return ApiResponse.ok(kb);
    }

    @PutMapping("/{id}")
    public ApiResponse<KnowledgeBase> update(@PathVariable UUID id,
                                             @RequestBody KbCreateRequest req) {
        if (kbMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.KB_NOT_FOUND, id.toString());
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName(req.name());
        kb.setDescription(req.description());
        kb.setColor(req.color());
        kbMapper.update(kb);
        return ApiResponse.ok(kbMapper.selectById(id));
    }

    /** 删除知识库；document_knowledge_bases 关联行经 CASCADE 清理，不影响文档本体 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        if (kbMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.KB_NOT_FOUND, id.toString());
        }
        kbMapper.deleteById(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/documents/{documentId}")
    public ApiResponse<Void> bindDocument(@PathVariable UUID id, @PathVariable UUID documentId) {
        requireKb(id);
        requireDoc(documentId);
        kbMapper.bindDocument(documentId, List.of(id));
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    public ApiResponse<Void> unbindDocument(@PathVariable UUID id, @PathVariable UUID documentId) {
        requireKb(id);
        kbMapper.unbindDocument(documentId, id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/documents")
    public ApiResponse<List<DocumentEntity>> documents(@PathVariable UUID id) {
        requireKb(id);
        List<DocumentEntity> docs = kbMapper.selectDocumentsByKbId(id);
        docs.forEach(doc -> doc.setKbIds(List.of(id)));
        return ApiResponse.ok(docs);
    }

    private void requireKb(UUID id) {
        if (kbMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.KB_NOT_FOUND, id.toString());
        }
    }

    private void requireDoc(UUID id) {
        if (documentMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.DOC_NOT_FOUND, id.toString());
        }
    }
}
