package com.codepilot;

import com.codepilot.service.RAGService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CodePilotPlugin {

    // 使用新的 ProjectActivity 接口
    public static class ProjectStartupActivity implements ProjectActivity {
        @Nullable
        @Override
        public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
            // 项目启动时初始化RAG服务
            RAGService ragService = RAGService.getInstance(project);

            // 在后台线程初始化
            new Thread(() -> {
                try {
                    ragService.initialize();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            return Unit.INSTANCE;
        }
    }
}