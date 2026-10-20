package cn.kong.eon.rag.api;

import cn.kong.eon.rag.api.dto.ChunkEditRequests;
import cn.kong.eon.rag.chunk.ChunkEditService;
import cn.kong.eon.rag.common.api.ApiResponse;
import cn.kong.eon.rag.repo.entity.ChildChunk;
import cn.kong.eon.rag.repo.entity.ParentChunk;
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
 * 父子块编辑接口：父块编辑/删除，子块编辑/删除/合并/拆分。
 */
@RestController
@RequestMapping("/api/documents/{docId}")
public class DocumentChunkController {

    private final ChunkEditService chunkEditService;

    public DocumentChunkController(ChunkEditService chunkEditService) {
        this.chunkEditService = chunkEditService;
    }

    @GetMapping("/parent-chunks/{parentId}/children")
    public ApiResponse<List<ChildChunk>> children(@PathVariable UUID docId,
                                                  @PathVariable UUID parentId) {
        return ApiResponse.ok(chunkEditService.listChildren(docId, parentId));
    }

    @PutMapping("/parent-chunks/{parentId}")
    public ApiResponse<ParentChunk> updateParent(
            @PathVariable UUID docId,
            @PathVariable UUID parentId,
            @RequestBody ChunkEditRequests.ParentUpdate request) {
        return ApiResponse.ok(chunkEditService.updateParent(docId, parentId, request));
    }

    @DeleteMapping("/parent-chunks/{parentId}")
    public ApiResponse<Void> deleteParent(@PathVariable UUID docId,
                                          @PathVariable UUID parentId) {
        chunkEditService.deleteParent(docId, parentId);
        return ApiResponse.ok();
    }

    @PutMapping("/parent-chunks/{parentId}/children/{childId}")
    public ApiResponse<ChildChunk> updateChild(
            @PathVariable UUID docId,
            @PathVariable UUID parentId,
            @PathVariable UUID childId,
            @RequestBody ChunkEditRequests.ChildUpdate request) {
        return ApiResponse.ok(chunkEditService.updateChild(docId, parentId, childId, request));
    }

    @DeleteMapping("/parent-chunks/{parentId}/children/{childId}")
    public ApiResponse<Void> deleteChild(@PathVariable UUID docId,
                                         @PathVariable UUID parentId,
                                         @PathVariable UUID childId) {
        chunkEditService.deleteChild(docId, parentId, childId);
        return ApiResponse.ok();
    }

    @PostMapping("/parent-chunks/{parentId}/children/merge")
    public ApiResponse<ChildChunk> mergeChildren(
            @PathVariable UUID docId,
            @PathVariable UUID parentId,
            @RequestBody ChunkEditRequests.Merge request) {
        return ApiResponse.ok(chunkEditService.mergeChildren(docId, parentId, request));
    }

    @PostMapping("/parent-chunks/{parentId}/children/{childId}/split")
    public ApiResponse<List<ChildChunk>> splitChild(
            @PathVariable UUID docId,
            @PathVariable UUID parentId,
            @PathVariable UUID childId,
            @RequestBody ChunkEditRequests.Split request) {
        return ApiResponse.ok(chunkEditService.splitChild(docId, parentId, childId, request));
    }
}
