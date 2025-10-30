package com.codepilot.actions;

import com.codepilot.service.LLMService;
import com.codepilot.util.CodeCleanupUtil;
import com.codepilot.util.PromptLoader;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
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

/**
 * 改进的Git提交消息生成器
 * 增强功能：
 * 1. 更智能的diff处理
 * 2. 详细的日志输出
 * 3. 更好的错误处理
 * 4. 支持中英文提交消息
 */
public class GenerateCommitMessageAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(GenerateCommitMessageAction.class);
    private static final int MAX_DIFF_LINES = 1000;  // 增加到1000行
    private static final int MAX_DIFF_CHARS = 50000; // 字符数限制

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
                    // 获取git diff
                    GitDiffResult diffResult = getGitDiffWithStats(project);

                    if (diffResult == null || diffResult.isEmpty()) {
                        showError("没有检测到暂存的代码变更，请先使用 'git add' 暂存文件");
                        return;
                    }

                    // 日志输出diff信息
                    LOG.info("=== GIT DIFF ANALYSIS ===");
                    LOG.info("Changed files: " + diffResult.filesChanged);
                    LOG.info("Insertions: " + diffResult.insertions);
                    LOG.info("Deletions: " + diffResult.deletions);
                    LOG.info("Diff lines: " + diffResult.diffLines);
                    LOG.info("Diff truncated: " + diffResult.isTruncated);

                    indicator.setText("正在生成提交消息...");

                    LLMService llmService = LLMService.getInstance(project);

                    // 构建增强的提示
                    Map<String, String> variables = new HashMap<>();
                    variables.put("diff", diffResult.diff);
                    variables.put("stats", diffResult.getStatsString());
                    variables.put("project_name", project.getName());

                    // 使用提示模板
                    String prompt = PromptLoader.formatPrompt("commit_message_prompt", variables);

                    LOG.info("Prompt length: " + prompt.length() + " characters");

                    String rawMessage = llmService.generateResponse(prompt);

                    // 清理生成的消息
                    String message = CodeCleanupUtil.cleanCommitMessage(rawMessage);

                    LOG.info("=== GENERATED COMMIT MESSAGE ===");
                    LOG.info(message);
                    LOG.info("=== END COMMIT MESSAGE ===");

                    // 设置提交消息或复制到剪贴板
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (commitMessageControl != null) {
                            commitMessageControl.setCommitMessage(message);
                            Messages.showInfoMessage(
                                    project,
                                    "提交消息已自动填入",
                                    "成功"
                            );
                        } else {
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
                    LOG.error("Failed to generate commit message", ex);
                    showError("生成提交消息失败：" + ex.getMessage());
                }
            }
        });
    }

    /**
     * 获取git diff及统计信息
     */
    private GitDiffResult getGitDiffWithStats(Project project) {
        try {
            GitRepositoryManager manager = GitRepositoryManager.getInstance(project);
            GitRepository repository = manager.getRepositories().stream().findFirst().orElse(null);

            if (repository == null) {
                LOG.warn("No Git repository found");
                return null;
            }

            String projectPath = project.getBasePath();

            // 先获取统计信息
            GitDiffResult result = new GitDiffResult();
            getGitStats(projectPath, result);

            // 获取详细diff
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--cached", "--no-color");
            pb.directory(new java.io.File(projectPath));

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );

            StringBuilder diff = new StringBuilder();
            String line;
            int lineCount = 0;
            int charCount = 0;

            while ((line = reader.readLine()) != null) {
                // 跳过二进制文件信息
                if (line.contains("Binary files") || line.startsWith("diff --git")) {
                    if (line.contains("Binary files")) {
                        diff.append("[二进制文件变更]\n");
                        continue;
                    }
                }

                // 检查是否超过限制
                if (lineCount >= MAX_DIFF_LINES || charCount >= MAX_DIFF_CHARS) {
                    result.isTruncated = true;
                    diff.append("\n... (diff已截断，共 ").append(result.filesChanged)
                            .append(" 个文件变更)\n");
                    break;
                }

                diff.append(line).append("\n");
                lineCount++;
                charCount += line.length();
            }

            process.waitFor();
            result.diff = diff.toString();
            result.diffLines = lineCount;

            return result.isEmpty() ? null : result;

        } catch (Exception e) {
            LOG.error("Failed to get git diff", e);
            return null;
        }
    }

    /**
     * 获取git统计信息
     */
    private void getGitStats(String projectPath, GitDiffResult result) {
        try {
            // 使用 git diff --cached --stat 获取统计信息
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--cached", "--stat");
            pb.directory(new java.io.File(projectPath));

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );

            String line;
            while ((line = reader.readLine()) != null) {
                // 解析最后一行的统计信息
                if (line.matches(".*\\d+ file.*changed.*")) {
                    String[] parts = line.trim().split(",");
                    for (String part : parts) {
                        part = part.trim();
                        if (part.contains("file")) {
                            result.filesChanged = extractNumber(part);
                        } else if (part.contains("insertion")) {
                            result.insertions = extractNumber(part);
                        } else if (part.contains("deletion")) {
                            result.deletions = extractNumber(part);
                        }
                    }
                }
            }

            process.waitFor();
        } catch (Exception e) {
            LOG.warn("Failed to get git stats", e);
        }
    }

    /**
     * 从字符串中提取数字
     */
    private int extractNumber(String str) {
        String numbers = str.replaceAll("[^0-9]", "");
        return numbers.isEmpty() ? 0 : Integer.parseInt(numbers);
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

    /**
     * Git diff结果包装类
     */
    private static class GitDiffResult {
        String diff = "";
        int filesChanged = 0;
        int insertions = 0;
        int deletions = 0;
        int diffLines = 0;
        boolean isTruncated = false;

        boolean isEmpty() {
            return diff == null || diff.trim().isEmpty();
        }

        String getStatsString() {
            return String.format("%d files changed, %d insertions(+), %d deletions(-)",
                    filesChanged, insertions, deletions);
        }
    }
}
