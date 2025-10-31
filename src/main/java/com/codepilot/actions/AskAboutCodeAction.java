package com.codepilot.actions;

import com.codepilot.ui.CodePilotToolWindow;
import com.codepilot.ui.CodePilotToolWindowFactory;
import com.codepilot.ui.CodeQuestionDialog;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

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

        // 获取当前编辑器对象
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }

        // 获取选中的文本内容
        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            return;
        }

        // 显示问题输入对话框
        CodeQuestionDialog dialog = new CodeQuestionDialog(project, selectedText);
        if (dialog.showAndGet()) {
            String question = dialog.getQuestion();
            if (question != null && !question.trim().isEmpty()) {
                // 将问题和代码发送到聊天窗口
                sendToChat(project, selectedText, question);
            }
        }
    }

    /**
     * 将问题和代码发送到聊天窗口
     */
    private void sendToChat(Project project, String code, String question) {
        // 获取 CodePilot 工具窗口
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("CodePilot");

        if (toolWindow != null) {
            // 显示工具窗口并发送消息
            toolWindow.show(() -> {
                // 获取窗口内容管理器中的第一个内容
                Content content = toolWindow.getContentManager().getContent(0);
                if (content != null) {
                    // 从 UserData 获取 CodePilotToolWindow 实例
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
        // 获取编辑器和项目对象
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();

        // 检查是否有选中的文本
        boolean hasSelection = editor != null &&
                editor.getSelectionModel().hasSelection();

        // 根据是否选中文本来更新按钮的显示状态
        e.getPresentation().setEnabledAndVisible(project != null && hasSelection);
    }
}