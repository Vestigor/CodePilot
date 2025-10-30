package com.codepilot.model;

public class DocumentChunk {
    private String content;
    private String documentName;
    private int pageNumber;
    private double[] embedding;
    private double similarity;

    public DocumentChunk(String content, String documentName, int pageNumber) {
        this.content = content;
        this.documentName = documentName;
        this.pageNumber = pageNumber;
    }

    // Getters and Setters
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }

    public int getPageNumber() { return pageNumber; }
    public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }

    public double[] getEmbedding() { return embedding; }
    public void setEmbedding(double[] embedding) { this.embedding = embedding; }

    public double getSimilarity() { return similarity; }
    public void setSimilarity(double similarity) { this.similarity = similarity; }

    public String getSource() {
        return documentName + " (第 " + pageNumber + " 页)";
    }
}
