package com.codepilot.service;

import com.codepilot.model.ChatMessage;
import com.codepilot.model.DocumentChunk;
import com.codepilot.util.ConfigManager;
import com.codepilot.util.PromptLoader;
import com.codepilot.util.StreamingOutputHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.*;
import java.util.stream.Collectors;

@Service(Service.Level.PROJECT)
public final class RAGService {
    private static final Logger LOG = Logger.getInstance(RAGService.class);
    private final Project project;
    private final DocumentProcessorService documentProcessor;
    private final VectorStoreService vectorStore;
    private final LLMService llmService;
    private final ConfigManager configManager;
    private boolean isInitialized = false;

    public RAGService(Project project) {
        this.project = project;
        this.documentProcessor = DocumentProcessorService.getInstance(project);
        this.vectorStore = VectorStoreService.getInstance(project);
        this.llmService = LLMService.getInstance(project);
        this.configManager = ApplicationManager.getApplication().getService(ConfigManager.class);
    }

    public static RAGService getInstance(Project project) {
        return project.getService(RAGService.class);
    }

    public void initialize() {
        if (isInitialized) {
            return;
        }

        LOG.info("Initializing RAG Service...");

        // 处理所有文档
        List<DocumentChunk> chunks = documentProcessor.processAllDocuments();

        // 索引文档块
        vectorStore.indexChunks(chunks);

        isInitialized = true;
        LOG.info("RAG Service initialized with " + chunks.size() + " chunks");
    }

    public void answerQuestion(String question, StreamingOutputHandler handler) {
        if (!isInitialized) {
            handler.appendLine("正在初始化知识库，请稍候...\n");
            initialize();
        }

        // 检索相关文档
        List<DocumentChunk> relevantChunks = vectorStore.search(question,
                configManager.getMaxRetrievalResults());

        List<String> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();

        if (!relevantChunks.isEmpty()) {
            for (DocumentChunk chunk : relevantChunks) {
                context.append(chunk.getContent()).append("\n\n");
                sources.add(chunk.getSource());
            }
        }

        // 构建提示词
        Map<String, String> variables = new HashMap<>();
        variables.put("context", context.toString());
        variables.put("question", question);

        String prompt = PromptLoader.formatPrompt("qa_prompt", variables);

        // 生成回答
        llmService.generateStreamingResponse(prompt, new StreamingOutputHandler(null) {
            @Override
            public void appendToken(String token) {
                handler.appendToken(token);
            }
        });

        // 添加引用来源
        handler.appendLine("\n\n【参考来源】");
        if (!sources.isEmpty()) {
            for (String source : sources) {
                handler.appendLine("- " + source);
            }
        } else {
            handler.appendLine("本回答基于通识知识，未引用课程资料");
        }
        handler.appendLine("\n\n");
    }

    public ChatMessage answerQuestionSync(String question) {
        if (!isInitialized) {
            initialize();
        }

        // 检索相关文档
        List<DocumentChunk> relevantChunks = vectorStore.search(question,
                configManager.getMaxRetrievalResults());

        List<String> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();

        if (!relevantChunks.isEmpty()) {
            for (DocumentChunk chunk : relevantChunks) {
                context.append(chunk.getContent()).append("\n\n");
                sources.add(chunk.getSource());
            }
        }

        // 构建提示词
        Map<String, String> variables = new HashMap<>();
        variables.put("context", context.toString());
        variables.put("question", question);

        String prompt = PromptLoader.formatPrompt("qa_prompt", variables);

        // 生成回答
        String answer = llmService.generateResponse(prompt);

        ChatMessage response = new ChatMessage("CodePilot", answer);
        if (!sources.isEmpty()) {
            response.setSources(sources);
        }

        return response;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public int getIndexedChunkCount() {
        return vectorStore.getChunkCount();
    }
}
