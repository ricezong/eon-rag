package cn.kong.eon.rag.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 三路混合检索参数。
 */
@ConfigurationProperties(prefix = "kb.search")
public class KbSearchProperties {

    /** 向量通道召回子块数 */
    private int vectorTopK = 20;
    /** zhparser 中文全文通道召回子块数 */
    private int fulltextTopK = 20;
    /** pg_trgm 模糊通道召回子块数 */
    private int fuzzyTopK = 10;
    /** pg_trgm 相似度阈值（中文密度高，由默认 0.3 调低） */
    private double fuzzySimilarityThreshold = 0.2;
    /** RRF 倒数秩融合平滑常数 */
    private int rrfK = 60;
    /** RRF 融合后保留的候选子块数（送 Rerank 的输入宽度） */
    private int rrfTopN = 50;
    /** Rerank 后保留进入父块归并的子块数 */
    private int rerankTopN = 15;
    /** 父块归并时每多命中一个子块的加分权重 α */
    private double parentHitBonusWeight = 0.05;
    /** 向量通道是否启用 HyDE 假设性文档改写 */
    private boolean hydeEnabled = false;

    public int getVectorTopK() {
        return vectorTopK;
    }

    public void setVectorTopK(int vectorTopK) {
        this.vectorTopK = vectorTopK;
    }

    public int getFulltextTopK() {
        return fulltextTopK;
    }

    public void setFulltextTopK(int fulltextTopK) {
        this.fulltextTopK = fulltextTopK;
    }

    public int getFuzzyTopK() {
        return fuzzyTopK;
    }

    public void setFuzzyTopK(int fuzzyTopK) {
        this.fuzzyTopK = fuzzyTopK;
    }

    public double getFuzzySimilarityThreshold() {
        return fuzzySimilarityThreshold;
    }

    public void setFuzzySimilarityThreshold(double fuzzySimilarityThreshold) {
        this.fuzzySimilarityThreshold = fuzzySimilarityThreshold;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public int getRrfTopN() {
        return rrfTopN;
    }

    public void setRrfTopN(int rrfTopN) {
        this.rrfTopN = rrfTopN;
    }

    public int getRerankTopN() {
        return rerankTopN;
    }

    public void setRerankTopN(int rerankTopN) {
        this.rerankTopN = rerankTopN;
    }

    public double getParentHitBonusWeight() {
        return parentHitBonusWeight;
    }

    public void setParentHitBonusWeight(double parentHitBonusWeight) {
        this.parentHitBonusWeight = parentHitBonusWeight;
    }

    public boolean isHydeEnabled() {
        return hydeEnabled;
    }

    public void setHydeEnabled(boolean hydeEnabled) {
        this.hydeEnabled = hydeEnabled;
    }
}
