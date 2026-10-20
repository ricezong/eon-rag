package cn.kong.eon.rag.repo.entity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文档主表实体。
 */
public class DocumentEntity {

    private UUID id;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String status;
    private String parseMode;
    private String errorMessage;
    private Integer parentCount;
    private Integer childCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    /** 所属知识库（非表字段，由接口回填） */
    private List<UUID> kbIds;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getParseMode() {
        return parseMode;
    }

    public void setParseMode(String parseMode) {
        this.parseMode = parseMode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getParentCount() {
        return parentCount;
    }

    public void setParentCount(Integer parentCount) {
        this.parentCount = parentCount;
    }

    public Integer getChildCount() {
        return childCount;
    }

    public void setChildCount(Integer childCount) {
        this.childCount = childCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<UUID> getKbIds() {
        return kbIds;
    }

    public void setKbIds(List<UUID> kbIds) {
        this.kbIds = kbIds;
    }
}
