package cn.kong.eon.rag.repo.entity;

import java.util.UUID;

/**
 * 文档与知识库的绑定关系。
 */
public class DocumentKbBinding {

    private UUID documentId;
    private UUID knowledgeBaseId;

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(UUID knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }
}
