package com.codepilot.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * 代码问题输入对话框类，用于显示输入问题的界面
 */
public class CodeQuestionDialog extends DialogWrapper {
    private final String code;  // 选中的代码
    private JTextArea questionArea;  // 问题输入框
    private JTextArea codePreviewArea;  // 代码预览框

    public CodeQuestionDialog(@Nullable Project project, String code) {
        super(project);
        this.code = code;
        setTitle("Ask a Question About the Code");
        setModal(true);
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(JBUI.Borders.empty(10));
        panel.setPreferredSize(new Dimension(600, 400));

        // 上半部分：问题输入
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.add(new JLabel("Please enter your question about the selected code:"), BorderLayout.NORTH);

        questionArea = new JTextArea(5, 50);
        questionArea.setLineWrap(true);
        questionArea.setWrapStyleWord(true);
        questionArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        JScrollPane questionScroll = new JScrollPane(questionArea);
        topPanel.add(questionScroll, BorderLayout.CENTER);

        // 添加示例问题
        JLabel hintLabel = new JLabel("<html><i>For example: What does this code do? Any suggestions for improvement? How can I optimize performance?</i></html>");
        hintLabel.setForeground(Color.GRAY);
        topPanel.add(hintLabel, BorderLayout.SOUTH);

        panel.add(topPanel, BorderLayout.NORTH);

        // 下半部分：代码预览
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.add(new JLabel("Selected Code:"), BorderLayout.NORTH);

        codePreviewArea = new JTextArea();
        codePreviewArea.setText(code);
        codePreviewArea.setEditable(false);
        codePreviewArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        JScrollPane codeScroll = new JScrollPane(codePreviewArea);
        bottomPanel.add(codeScroll, BorderLayout.CENTER);

        panel.add(bottomPanel, BorderLayout.CENTER);

        // 聚焦到问题输入框
        SwingUtilities.invokeLater(() -> questionArea.requestFocus());

        return panel;
    }

    @Override
    protected void createDefaultActions() {
        super.createDefaultActions();
        setOKButtonText("Send Question");
        setCancelButtonText("Cancel");
    }

    /**
     * 获取用户输入的问题文本
     */
    public String getQuestion() {
        return questionArea != null ? questionArea.getText().trim() : "";
    }
}