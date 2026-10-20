package cn.kong.eon.rag.repo.mapper;

import cn.kong.eon.rag.repo.entity.ChildChunk;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * 子块表 Mapper：三路混合检索 + 手动向量化写入 + 编辑浏览 + 降级邻接窗口。
 */
public interface ChildChunkMapper {

    /* ---------- 三路召回（kbIds 为空时不过滤知识库） ---------- */

    /**
     * 通道 1：向量余弦检索。
     *
     * @param queryVector 查询向量 PG 文本格式（"[v1,v2,...]"），XML 内 ::vector 强转
     */
    List<ChildChunk> searchByVector(@Param("queryVector") String queryVector,
                                    @Param("kbIds") List<UUID> kbIds,
                                    @Param("topK") int topK);

    /** 通道 2：zhparser 中文全文检索（websearch_to_tsquery 防特殊字符报错） */
    List<ChildChunk> searchByFulltext(@Param("rawQuery") String rawQuery,
                                      @Param("kbIds") List<UUID> kbIds,
                                      @Param("topK") int topK);

    /**
     * 通道 3 前置：同事务内调低 Trigram 相似度阈值。
     * SET LOCAL 仅在当前事务内生效，必须与 searchByFuzzy 处于同一事务。
     */
    void setFuzzyThresholdLocal(@Param("threshold") double threshold);

    /** 通道 3：Trigram 模糊匹配（WHERE content % q 走 gin_trgm_ops 索引） */
    List<ChildChunk> searchByFuzzy(@Param("rawQuery") String rawQuery,
                                    @Param("kbIds") List<UUID> kbIds,
                                    @Param("topK") int topK);

    /* ---------- 摄入写入 ---------- */

    int insertBatch(@Param("list") List<ChildChunk> chunks);

    /** 新增单个子块（拆分子块使用） */
    int insert(ChildChunk chunk);

    /** 手动向量化：加载文档下所有 embedding 为空的子块 */
    List<ChildChunk> selectUnindexedByDocumentId(@Param("documentId") UUID documentId);

    /** 写入单个子块向量 */
    int updateEmbedding(@Param("id") UUID id, @Param("embedding") String embedding);

    long countUnindexedByDocumentId(@Param("documentId") UUID documentId);

    /* ---------- 编辑与浏览 ---------- */

    /** 按父块加载全部子块，含 context_prefix，不含 embedding */
    List<ChildChunk> selectByParentId(@Param("parentId") UUID parentId);

    ChildChunk selectById(@Param("id") UUID id);

    /** 仅当内容未被并发修改时更新子块文本、向量与计数 */
    int updateContentIfUnchanged(@Param("id") UUID id,
                                 @Param("oldContent") String oldContent,
                                 @Param("content") String content,
                                 @Param("contextPrefix") String contextPrefix,
                                 @Param("embedding") String embedding,
                                 @Param("tokenCount") int tokenCount,
                                 @Param("meta") String meta);

    int updateSeq(@Param("id") UUID id, @Param("seq") int seq);

    int deleteById(@Param("id") UUID id);

    int deleteByParentId(@Param("parentId") UUID parentId);

    long countByDocumentId(@Param("documentId") UUID documentId);

    /* ---------- 降级与归并 ---------- */

    /**
     * 按 parent_id 与相邻 seq 加载左右邻接子块（超长父块降级为局部子块窗口）。
     * 依赖 UNIQUE (parent_id, seq) 充当 B-Tree 索引。
     */
    List<ChildChunk> selectAdjacent(@Param("parentId") UUID parentId,
                                    @Param("centerSeq") int centerSeq,
                                    @Param("before") int before,
                                    @Param("after") int after);

    int deleteByDocumentId(@Param("documentId") UUID documentId);
}
