package cn.kong.eon.rag.common.enums;

/**
 * 文档处理状态。
 */
public enum DocumentStatus {
    PROCESSING,
    CHUNKED,
    INDEXING,
    INDEX_FAILED,
    COMPLETED,
    NEEDS_OCR,
    FAILED
}
