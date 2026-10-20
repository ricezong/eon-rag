package cn.kong.eon.rag.chunk;

import java.util.Map;

/**
 * 分块中间产物（未落库，无主键）。
 */
public final class ChunkDraft {

    private ChunkDraft() {
    }

    /**
     * 父块草稿。
     *
     * @param title      最近一次章节标题（可为空）
     * @param kind       TEXT / TABLE / SLIDE，尾块合并按 kind 匹配"同类型"
     * @param content    父块正文
     * @param tokenCount Token 数
     * @param meta       透传块级元信息（sheet_name/rows/slide_number/page_start/page_end 等）
     */
    public record Parent(String title, BlockKind kind, String content,
                         int tokenCount, Map<String, String> meta) {
    }

    /**
     * 子块草稿（Token 计数在写入实体时完成）。
     */
    public record Child(String content, int tokenCount) {
    }

    /** 父块类别（区别于 BlockType：所有普通文本归并为 TEXT） */
    public enum BlockKind {TEXT, TABLE, SLIDE}
}
