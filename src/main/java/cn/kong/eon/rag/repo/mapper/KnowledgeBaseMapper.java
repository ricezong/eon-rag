package cn.kong.eon.rag.repo.mapper;

import cn.kong.eon.rag.repo.entity.DocumentEntity;
import cn.kong.eon.rag.repo.entity.DocumentKbBinding;
import cn.kong.eon.rag.repo.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * 知识库与文档-知识库关联 Mapper。
 */
public interface KnowledgeBaseMapper {

    int insert(KnowledgeBase kb);

    KnowledgeBase selectById(@Param("id") UUID id);

    List<KnowledgeBase> selectAll();

    boolean existsByName(@Param("name") String name);

    int update(KnowledgeBase kb);

    int deleteById(@Param("id") UUID id);

    /* ---------- 文档-知识库多对多关联 ---------- */

    int bindDocument(@Param("documentId") UUID documentId,
                     @Param("knowledgeBaseIds") List<UUID> knowledgeBaseIds);

    int unbindDocument(@Param("documentId") UUID documentId,
                       @Param("knowledgeBaseId") UUID knowledgeBaseId);

    /** 全部文档-知识库绑定关系，用于批量回填文档所属知识库 */
    List<DocumentKbBinding> selectAllBindings();

    List<KnowledgeBase> selectByDocumentId(@Param("documentId") UUID documentId);

    /** 列出知识库下的全部文档 */
    List<DocumentEntity> selectDocumentsByKbId(@Param("kbId") UUID kbId);
}
