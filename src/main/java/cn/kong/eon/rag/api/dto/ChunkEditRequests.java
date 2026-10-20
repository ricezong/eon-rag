package cn.kong.eon.rag.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * 父/子块编辑请求体。
 */
public final class ChunkEditRequests {

    private ChunkEditRequests() {
    }

    /**
     * @param title           父块标题，可为空
     * @param content         父块正文
     * @param rebuildChildren 是否按新正文重建子块，缺省 true
     */
    public record ParentUpdate(String title, String content, Boolean rebuildChildren) {
    }

    public record ChildUpdate(String content) {
    }

    public record Merge(List<UUID> childIds) {
    }

    /** offset 为 Java 字符下标（前端 textarea 的 selectionStart） */
    public record Split(Integer offset) {
    }
}
