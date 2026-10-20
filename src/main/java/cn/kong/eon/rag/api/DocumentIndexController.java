package cn.kong.eon.rag.api;

import cn.kong.eon.rag.common.api.ApiResponse;
import cn.kong.eon.rag.common.enums.DocumentStatus;
import cn.kong.eon.rag.index.VectorizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 文档手动向量化入口。
 */
@RestController
@RequestMapping("/api/documents/{docId}")
public class DocumentIndexController {

    private final VectorizationService vectorizationService;

    public DocumentIndexController(VectorizationService vectorizationService) {
        this.vectorizationService = vectorizationService;
    }

    @PostMapping("/index")
    public ResponseEntity<ApiResponse<Map<String, String>>> index(@PathVariable UUID docId) {
        DocumentStatus status = vectorizationService.start(docId);
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(Map.of(
                        "documentId", docId.toString(),
                        "status", status.name())));
    }
}
