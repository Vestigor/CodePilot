package com.codepilot.model;

public class ModelConfig {
    private String name;
    private String displayName;
    private String baseUrl;
    private boolean supportsStreaming;

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public boolean isSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(boolean supportsStreaming) {
        this.supportsStreaming = supportsStreaming;
    }
}