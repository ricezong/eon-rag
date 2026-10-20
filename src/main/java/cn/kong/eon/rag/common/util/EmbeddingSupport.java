package cn.kong.eon.rag.common.util;

/**
 * 子块 embedding 的输入拼接与 pgvector 文本序列化。
 */
public final class EmbeddingSupport {

    private EmbeddingSupport() {
    }

    /** 有 context_prefix 时按「前缀 + 换行 + 正文」送入 embedding */
    public static String input(String contextPrefix, String content) {
        return contextPrefix == null || contextPrefix.isBlank()
                ? content
                : contextPrefix + "\n" + content;
    }

    /** float[] → PG vector 文本格式 "[v1,v2,...]" */
    public static String toPgVector(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }
}
