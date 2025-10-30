package com.codepilot.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class CodePilotToolWindowFactory implements ToolWindowFactory {

    // 定义一个 Key 用于存储 CodePilotToolWindow 实例
    public static final Key<CodePilotToolWindow> TOOL_WINDOW_KEY =
            Key.create("CodePilotToolWindow");

    @Override
    public void createToolWindowContent(@NotNull Project project,
                                        @NotNull ToolWindow toolWindow) {
        CodePilotToolWindow codePilotToolWindow = new CodePilotToolWindow(project);
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(
                codePilotToolWindow.getContent(), "", false);

        // 将实例存储到 Content 的 userData 中
        content.putUserData(TOOL_WINDOW_KEY, codePilotToolWindow);

        toolWindow.getContentManager().addContent(content);
    }
}

