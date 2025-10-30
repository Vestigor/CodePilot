package com.codepilot.actions;

import com.codepilot.service.LLMService;
import com.codepilot.util.PromptLoader;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.CommitMessageI;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class GenerateCommitMessageAction extends AnAction {

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

        CommitMessageI commitMessageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL);

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "生成提交消息", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("正在分析代码变更...");

                try {
                    String diff = getGitDiff(project);

                    if (diff == null || diff.trim().isEmpty()) {
                        showError("没有检测到暂存的代码变更，请先使用 'git add' 暂存文件");
                        return;
                    }

                    indicator.setText("正在生成提交消息...");

                    LLMService llmService = LLMService.getInstance(project);

                    Map<String, String> variables = new HashMap<>();
                    variables.put("diff", diff);

                    String prompt = PromptLoader.formatPrompt("commit_message_prompt", variables);
                    String message = llmService.generateResponse(prompt);

                    // 设置提交消息或复制到剪贴板
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (commitMessageControl != null) {
                            // 如果在提交对话框中，直接设置消息
                            commitMessageControl.setCommitMessage(message);
                            Messages.showInfoMessage(
                                    project,
                                    "提交消息已自动填入",
                                    "成功"
                            );
                        } else {
                            // 否则复制到剪贴板
                            CopyPasteManager.getInstance().setContents(new StringSelection(message));
                            Messages.showInfoMessage(
                                    project,
                                    "生成的提交消息已复制到剪贴板：\n\n" + message +
                                            "\n\n请打开提交窗口（Ctrl+K）并粘贴",
                                    "提交消息"
                            );
                        }
                    });

                } catch (Exception ex) {
                    showError("生成提交消息失败：" + ex.getMessage());
                }
            }
        });
    }

    private String getGitDiff(Project project) {
        try {
            GitRepositoryManager manager = GitRepositoryManager.getInstance(project);
            GitRepository repository = manager.getRepositories().stream().findFirst().orElse(null);

            if (repository == null) {
                return null;
            }

            String projectPath = project.getBasePath();
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--cached");
            pb.directory(new java.io.File(projectPath));

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );

            StringBuilder diff = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                diff.append(line).append("\n");
            }

            process.waitFor();
            return diff.toString();

        } catch (Exception e) {
            return null;
        }
    }

    private void showError(String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showErrorDialog(message, "错误");
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
}
