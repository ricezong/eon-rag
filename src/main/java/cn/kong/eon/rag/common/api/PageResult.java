package cn.kong.eon.rag.common.api;

import java.util.List;

/**
 * 分页响应体。
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return new PageResult<>(items, total, page, size);
    }
}
