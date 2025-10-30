package com.codepilot.ui;

import com.codepilot.service.LLMService;
import com.codepilot.util.PromptLoader;
import com.codepilot.util.StreamingOutputHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class CodeGenerationDialog extends DialogWrapper {
    private final Project project;
    private final Editor editor;
    private final int offset;
    private final String contextCode;
    private final LLMService llmService;

    private JTextArea requirementArea;
    private JTextArea codePreviewArea;
    private JButton generateButton;
    private JButton regenerateButton;
    private String generatedCode = "";

    public CodeGenerationDialog(Project project, Editor editor, int offset, String contextCode) {
        super(project);
        this.project = project;
        this.editor = editor;
        this.offset = offset;
        this.contextCode = contextCode;
        this.llmService = LLMService.getInstance(project);

        setTitle("根据描述生成代码");
        setModal(false);
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(JBUI.Borders.empty(10));
        panel.setPreferredSize(new Dimension(700, 500));

        // 需求输入区域
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.add(new JLabel("描述您的需求:"), BorderLayout.NORTH);

        requirementArea = new JTextArea(4, 50);
        requirementArea.setLineWrap(true);
        requirementArea.setWrapStyleWord(true);
        requirementArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        JScrollPane reqScrollPane = new JScrollPane(requirementArea);
        topPanel.add(reqScrollPane, BorderLayout.CENTER);

        generateButton = new JButton("生成代码");
        generateButton.addActionListener(e -> generateCode());
        topPanel.add(generateButton, BorderLayout.SOUTH);

        panel.add(topPanel, BorderLayout.NORTH);

        // 代码预览区域
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        centerPanel.add(new JLabel("生成的代码:"), BorderLayout.NORTH);

        codePreviewArea = new JTextArea();
        codePreviewArea.setEditable(false);
        codePreviewArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        codePreviewArea.setLineWrap(false);
        JScrollPane codeScrollPane = new JScrollPane(codePreviewArea);
        centerPanel.add(codeScrollPane, BorderLayout.CENTER);

        regenerateButton = new JButton("重新生成");
        regenerateButton.setEnabled(false);
        regenerateButton.addActionListener(e -> regenerateCode());
        centerPanel.add(regenerateButton, BorderLayout.SOUTH);

        panel.add(centerPanel, BorderLayout.CENTER);

        return panel;
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{
                new DialogWrapperAction("确认插入") {
                    @Override
                    protected void doAction(java.awt.event.ActionEvent e) {
                        insertCode();
                    }
                },
                new DialogWrapperAction("撤销") {
                    @Override
                    protected void doAction(java.awt.event.ActionEvent e) {
                        doCancelAction();
                    }
                }
        };
    }

    private void generateCode() {
        String requirement = requirementArea.getText().trim();
        if (requirement.isEmpty()) {
            Messages.showWarningDialog(project, "请输入代码生成需求", "警告");
            return;
        }

        generateButton.setEnabled(false);
        codePreviewArea.setText("正在生成代码...\n");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                Map<String, String> variables = new HashMap<>();
                variables.put("context", contextCode != null ? contextCode : "");
                variables.put("requirement", requirement);

                String prompt = PromptLoader.formatPrompt("code_generation_prompt", variables);

                StreamingOutputHandler handler = new StreamingOutputHandler(codePreviewArea);
                codePreviewArea.setText("");
                llmService.generateStreamingResponse(prompt, handler);
                generatedCode = handler.getCurrentContent();

                return null;
            }

            @Override
            protected void done() {
                generateButton.setEnabled(true);
                regenerateButton.setEnabled(true);
            }
        };
        worker.execute();
    }

    private void regenerateCode() {
        generateCode();
    }

    private void insertCode() {
        if (generatedCode.isEmpty()) {
            Messages.showWarningDialog(project, "没有可插入的代码", "警告");
            return;
        }

        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document document = editor.getDocument();
            document.insertString(offset, generatedCode + "\n");
        });

        close(OK_EXIT_CODE);
    }
}
