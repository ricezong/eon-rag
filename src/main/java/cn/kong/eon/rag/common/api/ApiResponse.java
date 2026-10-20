package cn.kong.eon.rag.common.api;

/**
 * 统一响应包装。
 */
public record ApiResponse<T>(String code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", null, data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>("OK", null, null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
