package cn.kong.eon.rag.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 父子两段式分块参数。
 */
@ConfigurationProperties(prefix = "kb.chunk")
public class KbChunkProperties {

    private final Parent parent = new Parent();
    private final Child child = new Child();

    public Parent getParent() {
        return parent;
    }

    public Child getChild() {
        return child;
    }

    public static class Parent {
        /** 父块 Token 上限 */
        private int maxTokens = 1200;
        /** 尾块最小 Token 数，不足则并入前一同类型文本父块 */
        private int minTokens = 200;
        /** 触发新父块的标题层级阈值（level <= 该值开新块） */
        private int headingLevel = 2;

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getMinTokens() {
            return minTokens;
        }

        public void setMinTokens(int minTokens) {
            this.minTokens = minTokens;
        }

        public int getHeadingLevel() {
            return headingLevel;
        }

        public void setHeadingLevel(int headingLevel) {
            this.headingLevel = headingLevel;
        }
    }

    public static class Child {
        /** 子块 Token 上限 */
        private int maxTokens = 320;
        /** 人工合并后的子块 Token 上限（普通编辑/拆分仍受 maxTokens 约束） */
        private int mergeMaxTokens = 640;
        /** 相邻子块滑窗重叠 Token 数 */
        private int overlapTokens = 48;

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getMergeMaxTokens() {
            return mergeMaxTokens;
        }

        public void setMergeMaxTokens(int mergeMaxTokens) {
            this.mergeMaxTokens = mergeMaxTokens;
        }

        public int getOverlapTokens() {
            return overlapTokens;
        }

        public void setOverlapTokens(int overlapTokens) {
            this.overlapTokens = overlapTokens;
        }
    }
}
