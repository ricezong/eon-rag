package cn.kong.eon.rag.repo.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 子块实体。三路混合检索的核心单元。
 */
public class ChildChunk {

    private UUID id;
    private UUID parentId;
    private UUID documentId;
    private Integer seq;
    private String content;
    private String contextPrefix;
    /** 向量以 PG 文本格式传参（如 [0.1,0.2,...]），避免 Java 侧 vector 类型依赖 */
    private String embedding;
    private Integer tokenCount;
    private String meta;
    private OffsetDateTime createdAt;
    /** 检索/融合/重排得分（非持久化字段） */
    private Double rawScore;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContextPrefix() {
        return contextPrefix;
    }

    public void setContextPrefix(String contextPrefix) {
        this.contextPrefix = contextPrefix;
    }

    public String getEmbedding() {
        return embedding;
    }

    public void setEmbedding(String embedding) {
        this.embedding = embedding;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public String getMeta() {
        return meta;
    }

    public void setMeta(String meta) {
        this.meta = meta;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Double getRawScore() {
        return rawScore;
    }

    public void setRawScore(Double rawScore) {
        this.rawScore = rawScore;
    }

    /**
     * 带打分的副本。融合与重排只产出副本、不回写入参实体，
     * 调用方持有的候选列表不会被检索链路悄悄改写。
     */
    public ChildChunk copyWithScore(double score) {
        ChildChunk copy = new ChildChunk();
        copy.id = this.id;
        copy.parentId = this.parentId;
        copy.documentId = this.documentId;
        copy.seq = this.seq;
        copy.content = this.content;
        copy.contextPrefix = this.contextPrefix;
        copy.embedding = this.embedding;
        copy.tokenCount = this.tokenCount;
        copy.meta = this.meta;
        copy.createdAt = this.createdAt;
        copy.rawScore = score;
        return copy;
    }
}
