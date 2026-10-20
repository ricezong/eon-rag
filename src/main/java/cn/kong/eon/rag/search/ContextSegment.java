package cn.kong.eon.rag.search;

import java.util.List;
import java.util.UUID;

/**
 * 检索上下文片段：父块归并裁剪后的最终产物，供 Prompt 渲染与 citations 事件。
 *
 * @param index      引用编号（从 1 开始，对应回答中的 [n] 角标）
 * @param title      父块章节标题（可为空）
 * @param documentId 来源文档
 * @param parentId   父块 id
 * @param text       送入模型的上下文文本（整父块或降级邻接窗口）
 * @param tokenCount 实际送入 Token 数
 * @param parentMeta 父块定位元数据 JSON（page_start/page_end/sheet_name/slide_number 等）
 * @param hits      命中的子块明细（引用定位）
 */
public record ContextSegment(
        int index,
        String title,
        UUID documentId,
        UUID parentId,
        String text,
        int tokenCount,
        String parentMeta,
        List<ChildRef> hits) {

    /** 命中子块引用 */
    public record ChildRef(UUID childId, int seq, double score) {
    }
}
