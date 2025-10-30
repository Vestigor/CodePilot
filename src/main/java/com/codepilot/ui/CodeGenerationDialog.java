package com.codepilot.ui;

import com.codepilot.service.LLMService;
import com.codepilot.util.CodeCleanupUtil;
import com.codepilot.util.PromptLoader;
import com.codepilot.util.StreamingOutputHandler;
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
    private final CodeCleanupUtil.InsertionContext insertionContext;
    private final LLMService llmService;

    private JTextArea requirementArea;
    private JTextArea codePreviewArea;
    private JButton generateButton;
    private JButton regenerateButton;
    private JCheckBox autoCleanCheckBox;
    private String generatedCode = "";
    private String rawGeneratedCode = "";

    public CodeGenerationDialog(Project project, Editor editor, int offset,
                                String contextCode, CodeCleanupUtil.InsertionContext insertionContext) {
        super(project);
        this.project = project;
        this.editor = editor;
        this.offset = offset;
        this.contextCode = contextCode;
        this.insertionContext = insertionContext;
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
        panel.setPreferredSize(new Dimension(800, 600));

        // 顶部面板 - 需求输入
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));

        JPanel labelPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        labelPanel.add(new JLabel("描述您的需求:"));

        // 显示当前上下文
        if (insertionContext.isInsideClass()) {
            labelPanel.add(new JLabel("（将在类内部生成）"));
        } else if (insertionContext.isInsideMethod()) {
            labelPanel.add(new JLabel("（将在方法内部生成）"));
        }
        topPanel.add(labelPanel, BorderLayout.NORTH);

        requirementArea = new JTextArea(5, 50);
        requirementArea.setLineWrap(true);
        requirementArea.setWrapStyleWord(true);
        requirementArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        JScrollPane reqScrollPane = new JScrollPane(requirementArea);
        topPanel.add(reqScrollPane, BorderLayout.CENTER);

        // 按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        generateButton = new JButton("生成代码");
        generateButton.addActionListener(e -> generateCode());
        buttonPanel.add(generateButton);

        autoCleanCheckBox = new JCheckBox("自动清理生成的代码", true);
        buttonPanel.add(autoCleanCheckBox);

        topPanel.add(buttonPanel, BorderLayout.SOUTH);
        panel.add(topPanel, BorderLayout.NORTH);

        // 中部面板 - 代码预览
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        centerPanel.add(new JLabel("生成的代码预览:"), BorderLayout.NORTH);

        codePreviewArea = new JTextArea();
        codePreviewArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        codePreviewArea.setLineWrap(false);
        codePreviewArea.setTabSize(4);
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
                new DialogWrapperAction("插入代码") {
                    @Override
                    protected void doAction(java.awt.event.ActionEvent e) {
                        insertCode();
                    }
                },
                new DialogWrapperAction("取消") {
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

                // 根据插入位置调整提示
                String promptName = "code_generation_prompt";
                if (insertionContext.isInsideMethod()) {
                    variables.put("requirement", requirement + " (在方法内部生成语句)");
                } else if (insertionContext.isInsideClass()) {
                    variables.put("requirement", requirement + " (在类内部生成方法或字段)");
                }

                String prompt = PromptLoader.formatPrompt(promptName, variables);

                StreamingOutputHandler handler = new StreamingOutputHandler(codePreviewArea) {
                    @Override
                    public void appendToken(String token) {
                        super.appendToken(token);
                        rawGeneratedCode = getCurrentContent();
                    }
                };

                codePreviewArea.setText("");
                llmService.generateStreamingResponse(prompt, handler);

                // 清理代码
                if (autoCleanCheckBox.isSelected()) {
                    generatedCode = CodeCleanupUtil.cleanGeneratedCode(rawGeneratedCode);

                    // 根据上下文调整代码
                    generatedCode = CodeCleanupUtil.adjustForContext(generatedCode, insertionContext);

                    SwingUtilities.invokeLater(() -> {
                        codePreviewArea.setText(generatedCode);
                        codePreviewArea.setCaretPosition(0);
                    });
                } else {
                    generatedCode = rawGeneratedCode;
                }

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

        // 验证代码是否适合插入
        if (!CodeCleanupUtil.isValidForInsertion(generatedCode, insertionContext)) {
            int result = Messages.showYesNoDialog(
                    project,
                    "生成的代码可能不适合当前插入位置。是否仍要插入？",
                    "警告",
                    Messages.getWarningIcon()
            );

            if (result != Messages.YES) {
                return;
            }
        }

        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document document = editor.getDocument();

            // 智能插入：添加适当的换行和缩进
            String codeToInsert = formatCodeForInsertion(generatedCode);
            document.insertString(offset, codeToInsert);
        });

        close(OK_EXIT_CODE);
    }

    private String formatCodeForInsertion(String code) {
        // 获取当前行的缩进
        Document document = editor.getDocument();
        int lineNumber = document.getLineNumber(offset);
        int lineStartOffset = document.getLineStartOffset(lineNumber);
        String line = document.getText().substring(lineStartOffset, offset);

        // 计算缩进
        int indentLevel = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') {
                indentLevel++;
            } else if (c == '\t') {
                indentLevel += 4;
            } else {
                break;
            }
        }

        // 添加缩进到生成的代码
        String indent = " ".repeat(indentLevel);
        String[] lines = code.split("\n");
        StringBuilder formatted = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                formatted.append("\n");
            }
            if (!lines[i].trim().isEmpty()) {
                formatted.append(indent).append(lines[i]);
            }
        }

        // 添加换行符
        if (!code.endsWith("\n")) {
            formatted.append("\n");
        }

        return formatted.toString();
    }
}