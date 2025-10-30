package com.codepilot.util;

import javax.swing.*;

public class StreamingOutputHandler {
    private final JTextArea outputArea;
    private final StringBuilder currentContent;
    private volatile boolean isCancelled;

    public StreamingOutputHandler(JTextArea outputArea) {
        this.outputArea = outputArea;
        this.currentContent = new StringBuilder();
        this.isCancelled = false;
    }

    public void appendToken(String token) {
        if (isCancelled) {
            return;
        }

        currentContent.append(token);
        SwingUtilities.invokeLater(() -> {
            outputArea.append(token);
            outputArea.setCaretPosition(outputArea.getDocument().getLength());
        });
    }

    public void appendLine(String line) {
        appendToken(line + "\n");
    }

    public String getCurrentContent() {
        return currentContent.toString();
    }

    public void cancel() {
        isCancelled = true;
    }

    public boolean isCancelled() {
        return isCancelled;
    }

    public void reset() {
        currentContent.setLength(0);
        isCancelled = false;
    }
}
