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

    // 相似度阈值，低于此值的检索结果将被忽略
    private static final double SIMILARITY_THRESHOLD = 0.5;

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

        // 过滤出真正相关的文档（相似度高于阈值）
        List<DocumentChunk> actuallyRelevantChunks = relevantChunks.stream()
                .filter(chunk -> chunk.getSimilarity() > SIMILARITY_THRESHOLD)
                .collect(Collectors.toList());

        List<String> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        boolean hasRelevantMaterial = !actuallyRelevantChunks.isEmpty();

        if (hasRelevantMaterial) {
            // 有相关材料，构建上下文
            for (DocumentChunk chunk : actuallyRelevantChunks) {
                context.append("【来源：").append(chunk.getSource()).append("】\n");
                context.append(chunk.getContent()).append("\n\n");

                // 收集唯一的来源
                String source = chunk.getSource();
                if (!sources.contains(source)) {
                    sources.add(source);
                }
            }

            LOG.info("Found " + actuallyRelevantChunks.size() +
                    " relevant chunks with similarity > " + SIMILARITY_THRESHOLD);
        } else {
            // 没有相关材料，使用通用知识回答
            LOG.info("No relevant chunks found with similarity > " + SIMILARITY_THRESHOLD +
                    ". Will answer based on general knowledge.");
        }

        // 构建提示词
        Map<String, String> variables = new HashMap<>();
        variables.put("context", context.toString());
        variables.put("question", question);

        // 根据是否有相关材料选择不同的提示模板
        String promptName = hasRelevantMaterial ? "qa_prompt" : "qa_general_prompt";
        String prompt = PromptLoader.formatPrompt(promptName, variables);

        // 生成回答
        llmService.generateStreamingResponse(prompt, new StreamingOutputHandler(null) {
            @Override
            public void appendToken(String token) {
                handler.appendToken(token);
            }
        });

        // 添加引用来源
        handler.appendLine("\n\n【参考来源】\n");
        if (hasRelevantMaterial) {
            // 有相关材料时，列出具体来源
            for (String source : sources) {
                handler.appendLine("- " + source);
            }
        } else {
            // 没有相关材料时，明确说明
            handler.appendLine("本回答基于通识知识，未引用课程资料");
        }
        handler.appendLine("\n");
    }

    public ChatMessage answerQuestionSync(String question) {
        if (!isInitialized) {
            initialize();
        }

        // 检索相关文档
        List<DocumentChunk> relevantChunks = vectorStore.search(question,
                configManager.getMaxRetrievalResults());

        // 过滤出真正相关的文档（相似度高于阈值）
        List<DocumentChunk> actuallyRelevantChunks = relevantChunks.stream()
                .filter(chunk -> chunk.getSimilarity() > SIMILARITY_THRESHOLD)
                .collect(Collectors.toList());

        List<String> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        boolean hasRelevantMaterial = !actuallyRelevantChunks.isEmpty();

        if (hasRelevantMaterial) {
            for (DocumentChunk chunk : actuallyRelevantChunks) {
                context.append("【来源：").append(chunk.getSource()).append("】\n");
                context.append(chunk.getContent()).append("\n\n");

                String source = chunk.getSource();
                if (!sources.contains(source)) {
                    sources.add(source);
                }
            }
        }

        // 构建提示词
        Map<String, String> variables = new HashMap<>();
        variables.put("context", context.toString());
        variables.put("question", question);

        String promptName = hasRelevantMaterial ? "qa_prompt" : "qa_general_prompt";
        String prompt = PromptLoader.formatPrompt(promptName, variables);

        // 生成回答
        String answer = llmService.generateResponse(prompt);

        ChatMessage response = new ChatMessage("CodePilot", answer);

        // 只有在真正有相关材料时才设置来源
        if (hasRelevantMaterial) {
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

    /**
     * 获取问题的相关性评分
     */
    public double getRelevanceScore(String question) {
        if (!isInitialized) {
            return 0.0;
        }

        List<DocumentChunk> chunks = vectorStore.search(question, 1);
        if (chunks.isEmpty()) {
            return 0.0;
        }

        return chunks.get(0).getSimilarity();
    }
}
