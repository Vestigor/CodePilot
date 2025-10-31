package com.codepilot.actions;

import com.codepilot.service.LLMService;
import com.codepilot.util.CodeCleanupUtil;
import com.codepilot.util.PromptLoader;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * 自动生成单元测试的动作类
 */
public class GenerateUnitTestAction extends AnAction {

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
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        // 检查编辑器和文件是否有效
        if (editor == null || psiFile == null) {
            return;
        }

        // 获取光标所在位置的元素
        PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
        PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);

        // 如果光标不在类或方法内，提示用户
        if (psiClass == null) {
            Messages.showWarningDialog(project, "Please place the cursor inside a class or method", "Hint");
            return;
        }

        // 获取用户输入的测试需求或场景
        String requirement = Messages.showInputDialog(
                project,
                "Describe the test scenario or requirement (e.g., test for null values, boundary conditions, etc.):",
                "Generate Unit Test",
                Messages.getQuestionIcon()
        );

        if (requirement == null || requirement.trim().isEmpty()) {
            return;
        }

        String sourceCode = psiClass.getText();
        String className = psiClass.getName();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Generating Unit Test", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Generating test code...");

                // 获取 LLM 服务实例
                LLMService llmService = LLMService.getInstance(project);

                // 构建传递给 LLM 的变量
                Map<String, String> variables = new HashMap<>();
                variables.put("code", sourceCode);
                variables.put("requirement", requirement);
                variables.put("className", className);

                // 格式化生成的提示模板
                String prompt = PromptLoader.formatPrompt("test_generation_prompt", variables);
                String rawTestCode = llmService.generateResponse(prompt);

                // 清理生成的测试代码
                // String testCode = CodeCleanupUtil.cleanTestCode(rawTestCode, className);

                // 在应用程序的事件调度线程中创建测试文件
                ApplicationManager.getApplication().invokeLater(() -> {
                    createTestFile(project, psiFile, className, rawTestCode);
                });
            }
        });
    }

    /**
     * 创建测试文件并将其添加到源代码所在目录
     */
    private void createTestFile(Project project, PsiFile sourceFile,
                                String className, String testCode) {
        try {
            PsiDirectory directory = sourceFile.getContainingDirectory();
            if (directory == null) {
                return;
            }

            String testFileName = className + "Test.java";

            // 在 write action 之前检查文件并询问用户
            PsiFile existingFile = directory.findFile(testFileName);
            if (existingFile != null) {
                int result = Messages.showYesNoDialog(
                        project,
                        "Test file already exists. Do you want to overwrite it?",
                        "Confirm",
                        Messages.getQuestionIcon()
                );
                if (result != Messages.YES) {
                    return; // 用户选择不覆盖，直接返回
                }
            }

            WriteCommandAction.runWriteCommandAction(project, () -> {
                // 删除已存在的文件
                if (existingFile != null) {
                    existingFile.delete();
                }

                // 创建新的测试文件并添加到目录中
                PsiFile testFile = PsiFileFactory.getInstance(project)
                        .createFileFromText(testFileName, JavaFileType.INSTANCE, testCode);

                directory.add(testFile);
            });

            Messages.showInfoMessage(
                    project,
                    "Test class has been successfully created: " + testFileName,
                    "Success"
            );

        } catch (Exception ex) {
            Messages.showErrorDialog(
                    project,
                    "Failed to create test file: " + ex.getMessage(),
                    "Error"
            );
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        // 确保当前文件是 Java 文件
        boolean isJavaFile = psiFile instanceof PsiJavaFile;
        e.getPresentation().setEnabledAndVisible(project != null && isJavaFile);
    }
}