package cn.kong.eon.rag.chunk;

/**
 * Token 计量契约。中文场景按 Token 而非字符切分，避免中英文切片失衡。
 */
public interface TokenCounter {

    /** 文本 Token 数 */
    int count(String text);

    /** 取文本末尾至多 maxTokens 个 Token 对应的内容（滑窗重叠前缀） */
    String tail(String text, int maxTokens);

    /** 取文本开头至多 maxTokens 个 Token 对应的内容（单句超限硬切） */
    String head(String text, int maxTokens);
}
