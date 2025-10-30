package com.codepilot.model;

import java.time.LocalDateTime;
import java.util.List;

public class ChatMessage {
    private String role; // "student" or "CodePilot"
    private String content;
    private List<String> sources;
    private LocalDateTime timestamp;
    private boolean isStreaming;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
        this.timestamp = LocalDateTime.now();
        this.isStreaming = false;
    }

    // Getters and Setters
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<String> getSources() { return sources; }
    public void setSources(List<String> sources) { this.sources = sources; }

    public LocalDateTime getTimestamp() { return timestamp; }

    public boolean isStreaming() { return isStreaming; }
    public void setStreaming(boolean streaming) { isStreaming = streaming; }

    public String getFormattedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(role).append("：").append(content).append("\n");

        if (sources != null && !sources.isEmpty()) {
            sb.append("\n【参考来源】\n");
            for (String source : sources) {
                sb.append("- ").append(source).append("\n");
            }
        } else if ("CodePilot".equals(role)) {
            sb.append("\n【参考来源】\n");
            sb.append("本回答基于通识知识，未引用课程资料\n");
        }

        sb.append("\n\n");
        return sb.toString();
    }
}
