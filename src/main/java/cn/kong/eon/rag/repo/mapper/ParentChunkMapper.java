package cn.kong.eon.rag.repo.mapper;

import cn.kong.eon.rag.repo.entity.ParentChunk;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * 父块表 Mapper。
 */
public interface ParentChunkMapper {

    int insertBatch(@Param("list") List<ParentChunk> chunks);

    /** 按 id 集合批量加载父块 */
    List<ParentChunk> selectByIds(@Param("ids") List<UUID> ids);

    /** 引用定位：按 id 取单个父块原文 */
    ParentChunk selectById(@Param("id") UUID id);

    List<ParentChunk> selectByDocumentId(@Param("documentId") UUID documentId);

    /** 按文档分页加载父块，offset 从 0 开始 */
    List<ParentChunk> selectPageByDocumentId(@Param("documentId") UUID documentId,
                                             @Param("offset") int offset,
                                             @Param("size") int size);

    /** 文档的父块总数 */
    long countByDocumentId(@Param("documentId") UUID documentId);

    /** 仅当内容未被并发修改时更新父块 */
    int updateContentIfUnchanged(@Param("id") UUID id,
                                 @Param("oldContent") String oldContent,
                                 @Param("title") String title,
                                 @Param("content") String content,
                                 @Param("tokenCount") int tokenCount);

    int updateSeq(@Param("id") UUID id, @Param("seq") int seq);

    int deleteById(@Param("id") UUID id);

    int deleteByDocumentId(@Param("documentId") UUID documentId);
}
