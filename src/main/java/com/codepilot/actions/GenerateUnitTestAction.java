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

        if (editor == null || psiFile == null) {
            return;
        }

        PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
        PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);

        if (psiClass == null) {
            Messages.showWarningDialog(project, "请将光标放在类或方法内", "提示");
            return;
        }

        String requirement = Messages.showInputDialog(
                project,
                "请描述测试场景或需求（例如：测试空值情况、边界条件等）：",
                "生成单元测试",
                Messages.getQuestionIcon()
        );

        if (requirement == null || requirement.trim().isEmpty()) {
            return;
        }

        String sourceCode = psiClass.getText();
        String className = psiClass.getName();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "生成单元测试", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("正在生成测试代码...");

                LLMService llmService = LLMService.getInstance(project);

                Map<String, String> variables = new HashMap<>();
                variables.put("code", sourceCode);
                variables.put("requirement", requirement);
                variables.put("className", className);

                String prompt = PromptLoader.formatPrompt("test_generation_prompt", variables);
                String rawTestCode = llmService.generateResponse(prompt);

                // 清理生成的测试代码
                String testCode = CodeCleanupUtil.cleanTestCode(rawTestCode, className);

                ApplicationManager.getApplication().invokeLater(() -> {
                    createTestFile(project, psiFile, className, testCode);
                });
            }
        });
    }

    private void createTestFile(Project project, PsiFile sourceFile,
                                String className, String testCode) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                PsiDirectory directory = sourceFile.getContainingDirectory();
                if (directory == null) {
                    return;
                }

                String testFileName = className + "Test.java";

                PsiFile existingFile = directory.findFile(testFileName);
                if (existingFile != null) {
                    int result = Messages.showYesNoDialog(
                            project,
                            "测试文件已存在，是否覆盖？",
                            "确认",
                            Messages.getQuestionIcon()
                    );
                    if (result == Messages.YES) {
                        existingFile.delete();
                    } else {
                        return;
                    }
                }

                PsiFile testFile = PsiFileFactory.getInstance(project)
                        .createFileFromText(testFileName, JavaFileType.INSTANCE, testCode);

                directory.add(testFile);

                Messages.showInfoMessage(
                        project,
                        "测试类已成功创建：" + testFileName,
                        "成功"
                );
            } catch (Exception ex) {
                Messages.showErrorDialog(
                        project,
                        "创建测试文件失败：" + ex.getMessage(),
                        "错误"
                );
            }
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        boolean isJavaFile = psiFile instanceof PsiJavaFile;
        e.getPresentation().setEnabledAndVisible(project != null && isJavaFile);
    }
}
