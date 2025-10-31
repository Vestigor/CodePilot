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
        // 设定操作执行的线程为后台线程
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        // 获取当前编辑器和PSI文件对象
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        if (editor == null || psiFile == null) {
            return;
        }

        // 获取光标当前位置的偏移量
        int offset = editor.getCaretModel().getOffset();

        // 获取当前位置的上下文信息（类、方法等）
        PsiElement element = psiFile.findElementAt(offset);
        CodeCleanupUtil.InsertionContext context = analyzeContext(element);

        // 获取上下文代码（用于展示代码框架等）
        String contextCode = getContextCode(editor, element);

        // 显示代码生成对话框
        CodeGenerationDialog dialog = new CodeGenerationDialog(
                project, editor, offset, contextCode, context
        );
        dialog.show();
    }

    /**
     * 分析当前位置的上下文，判断光标是否在类或方法内部
     */
    private CodeCleanupUtil.InsertionContext analyzeContext(PsiElement element) {
        boolean insideClass = false;
        boolean insideMethod = false;
        String surroundingCode = "";

        if (element != null) {
            // 获取当前位置的类和方法信息
            PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            PsiMethod psiMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);

            insideClass = (psiClass != null);
            insideMethod = (psiMethod != null);

            // 获取类的代码
            if (psiClass != null) {
                surroundingCode = psiClass.getText();
            }
        }

        // 返回上下文信息
        return new CodeCleanupUtil.InsertionContext(insideClass, insideMethod, surroundingCode);
    }

    /**
     * 获取当前编辑器中的上下文代码
     */
    private String getContextCode(Editor editor, PsiElement element) {
        // 如果光标在类内部，返回类的框架代码
        if (element != null) {
            PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            if (psiClass != null) {
                return getClassSkeleton(psiClass);  // 获取类的骨架结构
            }
        }

        // 获取文件的前500行作为上下文
        String fullText = editor.getDocument().getText();
        String[] lines = fullText.split("\n");
        int contextLines = Math.min(500, lines.length);
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < contextLines; i++) {
            context.append(lines[i]).append("\n");
        }
        return context.toString();
    }

    /**
     * 获取类的骨架结构，包括类声明、字段和方法签名
     */
    private String getClassSkeleton(PsiClass psiClass) {
        StringBuilder skeleton = new StringBuilder();

        // 获取包声明
        PsiFile file = psiClass.getContainingFile();
        if (file instanceof PsiJavaFile javaFile) {
            skeleton.append("package ").append(javaFile.getPackageName()).append(";\n\n");
        }

        // 添加类声明
        skeleton.append("public class ").append(psiClass.getName());
        PsiClass superClass = psiClass.getSuperClass();
        if (superClass != null && !superClass.getName().equals("Object")) {
            skeleton.append(" extends ").append(superClass.getName());
        }
        skeleton.append(" {\n");

        // 添加字段
        for (PsiField field : psiClass.getFields()) {
            skeleton.append("    ").append(field.getText()).append("\n");
        }

        // 添加方法签名
        for (PsiMethod method : psiClass.getMethods()) {
            skeleton.append("\n    ").append(getMethodSignature(method)).append("\n");
        }

        skeleton.append("}");
        return skeleton.toString();
    }

    /**
     * 获取方法的签名
     */
    private String getMethodSignature(PsiMethod method) {
        StringBuilder sig = new StringBuilder();

        // 获取方法的修饰符（public、private 等）
        if (method.getModifierList() != null) {
            sig.append(method.getModifierList().getText()).append(" ");
        }

        // 获取返回类型
        if (method.getReturnType() != null) {
            sig.append(method.getReturnType().getPresentableText()).append(" ");
        }

        // 获取方法名和参数
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

        // 确保编辑器和项目对象不为空
        e.getPresentation().setEnabledAndVisible(project != null && editor != null);
    }
}