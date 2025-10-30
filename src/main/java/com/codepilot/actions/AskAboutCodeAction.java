package com.codepilot.actions;

import com.codepilot.ui.CodePilotToolWindow;
import com.codepilot.ui.CodePilotToolWindowFactory;
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

        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }

        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            return;
        }

        String formattedCode = "```java\n" + selectedText + "\n```\n\n";

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
                        window.appendToInput(formattedCode);
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
}
