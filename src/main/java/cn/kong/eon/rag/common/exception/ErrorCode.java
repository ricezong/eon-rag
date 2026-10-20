package cn.kong.eon.rag.common.exception;

/**
 * 业务错误码。
 */
public enum ErrorCode {
    FILE_TOO_LARGE("FILE_TOO_LARGE", "文件超过大小上限"),
    UNSUPPORTED_FORMAT("UNSUPPORTED_FORMAT", "不支持的文件格式"),
    NEEDS_OCR("NEEDS_OCR", "疑似扫描件，无文本层，需 OCR 预处理"),
    PARSE_FAILED("PARSE_FAILED", "文档解析失败"),
    DOC_NOT_FOUND("DOC_NOT_FOUND", "文档不存在"),
    PARENT_NOT_FOUND("PARENT_NOT_FOUND", "父块不存在"),
    CHUNK_NOT_FOUND("CHUNK_NOT_FOUND", "分块不存在"),
    DOC_NOT_EDITABLE("DOC_NOT_EDITABLE", "文档当前状态不可编辑"),
    INDEX_CONFLICT("INDEX_CONFLICT", "文档当前状态不可向量化"),
    INVALID_CHUNK_OPERATION("INVALID_CHUNK_OPERATION", "分块操作不合法"),
    TOKEN_LIMIT_EXCEEDED("TOKEN_LIMIT_EXCEEDED", "分块超过 Token 上限"),
    EMBEDDING_FAILED("EMBEDDING_FAILED", "向量化失败"),
    CHUNK_CONFLICT("CHUNK_CONFLICT", "分块内容已被其他操作修改"),
    KB_NOT_FOUND("KB_NOT_FOUND", "知识库不存在"),
    KB_NAME_EXISTS("KB_NAME_EXISTS", "知识库名称已存在"),
    UNAUTHORIZED("UNAUTHORIZED", "API Key 缺失或错误"),
    CHAT_FAILED("CHAT_FAILED", "问答生成失败"),
    INTERNAL("INTERNAL", "系统内部错误");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
