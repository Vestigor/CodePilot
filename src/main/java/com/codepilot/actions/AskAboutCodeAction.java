package com.codepilot.actions;

import com.codepilot.ui.CodePilotToolWindow;
import com.codepilot.ui.CodePilotToolWindowFactory;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class AskAboutCodeAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }

        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            return;
        }

        // 显示问题输入对话框
        CodeQuestionDialog dialog = new CodeQuestionDialog(project, selectedText);
        if (dialog.showAndGet()) {
            String question = dialog.getQuestion();
            if (question != null && !question.trim().isEmpty()) {
                // 发送到聊天窗口
                sendToChat(project, selectedText, question);
            }
        }
    }

    private void sendToChat(Project project, String code, String question) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("CodePilot");

        if (toolWindow != null) {
            toolWindow.show(() -> {
                Content content = toolWindow.getContentManager().getContent(0);
                if (content != null) {
                    // 从 UserData 中获取 CodePilotToolWindow 实例
                    CodePilotToolWindow window = content.getUserData(
                            CodePilotToolWindowFactory.TOOL_WINDOW_KEY);

                    if (window != null) {
                        // 直接发送问题和代码，不是写入输入框
                        window.sendCodeQuestion(question, code);
                    }
                }
            });
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();

        boolean hasSelection = editor != null &&
                editor.getSelectionModel().hasSelection();

        e.getPresentation().setEnabledAndVisible(project != null && hasSelection);
    }

    /**
     * 代码问题输入对话框
     */
    private static class CodeQuestionDialog extends DialogWrapper {
        private final String code;
        private JTextArea questionArea;
        private JTextArea codePreviewArea;

        protected CodeQuestionDialog(@Nullable Project project, String code) {
            super(project);
            this.code = code;
            setTitle("询问关于代码的问题");
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
            topPanel.add(new JLabel("请输入您关于选中代码的问题："), BorderLayout.NORTH);

            questionArea = new JTextArea(5, 50);
            questionArea.setLineWrap(true);
            questionArea.setWrapStyleWord(true);
            questionArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
            JScrollPane questionScroll = new JScrollPane(questionArea);
            topPanel.add(questionScroll, BorderLayout.CENTER);

            // 添加示例问题
            JLabel hintLabel = new JLabel("<html><i>例如：这段代码的作用是什么？有什么改进建议？如何优化性能？</i></html>");
            hintLabel.setForeground(Color.GRAY);
            topPanel.add(hintLabel, BorderLayout.SOUTH);

            panel.add(topPanel, BorderLayout.NORTH);

            // 下半部分：代码预览
            JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
            bottomPanel.add(new JLabel("选中的代码："), BorderLayout.NORTH);

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
            setOKButtonText("发送问题");
            setCancelButtonText("取消");
        }

        public String getQuestion() {
            return questionArea != null ? questionArea.getText().trim() : "";
        }
    }
}
