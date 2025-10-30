package com.codepilot.model;

import java.util.List;

/**
 * Knowledge entry model for storing document chunks with embeddings
 */
public class KnowledgeEntry {
    private String chunkId;
    private String content;
    private List<Double> embedding;
    private String source;
    private int page;
    private String documentType; // pdf, docx, txt, pptx

    public KnowledgeEntry() {
        // Default constructor for JSON deserialization
    }

    public KnowledgeEntry(String chunkId, String content, String source, int page, String documentType) {
        this.chunkId = chunkId;
        this.content = content;
        this.source = source;
        this.page = page;
        this.documentType = documentType;
    }

    // Getters and Setters
    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<Double> getEmbedding() { return embedding; }
    public void setEmbedding(List<Double> embedding) { this.embedding = embedding; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    /**
     * Generate unique chunk ID
     */
    public static String generateChunkId(String source, int page, int chunkIndex) {
        String baseName = source.replaceAll("\\.\\w+$", "");
        return baseName + "_p" + page + "_c" + chunkIndex;
    }
}