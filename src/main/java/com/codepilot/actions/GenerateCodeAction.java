package com.codepilot.actions;

import com.codepilot.ui.CodeGenerationDialog;
import com.codepilot.util.CodeCleanupUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

public class GenerateCodeAction extends AnAction {

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

        int offset = editor.getCaretModel().getOffset();

        // 获取当前位置的上下文
        PsiElement element = psiFile.findElementAt(offset);
        CodeCleanupUtil.InsertionContext context = analyzeContext(element);

        // 获取上下文代码
        String contextCode = getContextCode(editor, element);

        // 显示改进的代码生成对话框
        CodeGenerationDialog dialog = new CodeGenerationDialog(
                project, editor, offset, contextCode, context
        );
        dialog.show();
    }

    private CodeCleanupUtil.InsertionContext analyzeContext(PsiElement element) {
        boolean insideClass = false;
        boolean insideMethod = false;
        String surroundingCode = "";

        if (element != null) {
            PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            PsiMethod psiMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);

            insideClass = (psiClass != null);
            insideMethod = (psiMethod != null);

            if (psiClass != null) {
                surroundingCode = psiClass.getText();
            }
        }

        return new CodeCleanupUtil.InsertionContext(insideClass, insideMethod, surroundingCode);
    }

    private String getContextCode(Editor editor, PsiElement element) {
        // 获取更智能的上下文
        if (element != null) {
            PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            if (psiClass != null) {
                // 只返回类的框架，不包含所有方法实现
                return getClassSkeleton(psiClass);
            }
        }

        // 获取当前文件的前100行作为上下文
        String fullText = editor.getDocument().getText();
        String[] lines = fullText.split("\n");
        int contextLines = Math.min(100, lines.length);
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < contextLines; i++) {
            context.append(lines[i]).append("\n");
        }
        return context.toString();
    }

    private String getClassSkeleton(PsiClass psiClass) {
        StringBuilder skeleton = new StringBuilder();

        // 包声明
        PsiFile file = psiClass.getContainingFile();
        if (file instanceof PsiJavaFile) {
            PsiJavaFile javaFile = (PsiJavaFile) file;
            skeleton.append("package ").append(javaFile.getPackageName()).append(";\n\n");
        }

        // 类声明
        skeleton.append("public class ").append(psiClass.getName());
        PsiClass superClass = psiClass.getSuperClass();
        if (superClass != null && !superClass.getName().equals("Object")) {
            skeleton.append(" extends ").append(superClass.getName());
        }
        skeleton.append(" {\n");

        // 字段
        for (PsiField field : psiClass.getFields()) {
            skeleton.append("    ").append(field.getText()).append("\n");
        }

        // 方法签名（不包含实现）
        for (PsiMethod method : psiClass.getMethods()) {
            skeleton.append("\n    ").append(getMethodSignature(method)).append("\n");
        }

        skeleton.append("}");
        return skeleton.toString();
    }

    private String getMethodSignature(PsiMethod method) {
        StringBuilder sig = new StringBuilder();

        // 修饰符
        if (method.getModifierList() != null) {
            sig.append(method.getModifierList().getText()).append(" ");
        }

        // 返回类型
        if (method.getReturnType() != null) {
            sig.append(method.getReturnType().getPresentableText()).append(" ");
        }

        // 方法名和参数
        sig.append(method.getName()).append("(");
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(params[i].getType().getPresentableText()).append(" ");
            sig.append(params[i].getName());
        }
        sig.append(") { ... }");

        return sig.toString();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        e.getPresentation().setEnabledAndVisible(project != null && editor != null);
    }
}
