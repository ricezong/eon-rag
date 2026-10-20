package cn.kong.eon.rag.repo.mapper;

import cn.kong.eon.rag.repo.entity.DocumentEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * 文档主表 Mapper。
 */
public interface DocumentMapper {

    int insert(DocumentEntity document);

    DocumentEntity selectById(@Param("id") UUID id);

    List<DocumentEntity> selectAll();

    int updateStatus(@Param("id") UUID id,
                     @Param("status") String status,
                     @Param("errorMessage") String errorMessage);

    /** 仅当当前状态匹配时更新，用于手动索引抢占 INDEXING */
    int updateStatusIfCurrent(@Param("id") UUID id,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("status") String status,
                              @Param("errorMessage") String errorMessage);

    /** 更新父子块计数并设置状态，同时清理错误信息 */
    int updateCountsAndStatus(@Param("id") UUID id,
                              @Param("parentCount") int parentCount,
                              @Param("childCount") int childCount,
                              @Param("status") String status);

    /** 只刷新计数，不改变状态与错误信息（切片编辑使用） */
    int updateCountsOnly(@Param("id") UUID id,
                         @Param("parentCount") int parentCount,
                         @Param("childCount") int childCount);

    /** 在编辑事务内按状态条件触发行更新，防止与手动索引并发 */
    int touchStatusIfCurrent(@Param("id") UUID id, @Param("status") String status);

    int deleteById(@Param("id") UUID id);
}
