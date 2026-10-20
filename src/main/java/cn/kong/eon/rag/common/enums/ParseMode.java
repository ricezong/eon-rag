package cn.kong.eon.rag.common.enums;

/**
 * 文档解析模式。
 */
public enum ParseMode {
    /** 自动路由：白名单格式优先云端，失败或非白名单走本地 */
    AUTO,
    /** 强制本地解析 */
    LOCAL,
    /** 强制云端 LlamaParse */
    CLOUD
}
