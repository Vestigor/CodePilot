package com.codepilot.service;

import com.codepilot.model.KnowledgeEntry;
import com.codepilot.util.ConfigManager;
import com.codepilot.util.KnowledgeBaseLoader;
import com.codepilot.util.PromptLoader;
import com.codepilot.util.StreamingOutputHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG服务，具备上下文感知能力
 */
@Service(Service.Level.PROJECT)
public final class RAGService {
    private static final Logger LOG = Logger.getInstance(RAGService.class);
    private final Project project;
    private final DocumentProcessorService documentProcessor;
    private final VectorStoreService vectorStore;
    private final LLMService llmService;
    private final ConfigManager configManager;
    private boolean isInitialized = false;
    private static final String PLUGIN_DATA_PATH = PathManager.getPluginsPath() + "/CodePilot/data";

    // 相似度阈值
    private static final double SIMILARITY_THRESHOLD = 0.5;
    private static final double HIGH_SIMILARITY_THRESHOLD = 0.7;

    public RAGService(Project project) {
        this.project = project;
        this.documentProcessor = DocumentProcessorService.getInstance(project);
        this.vectorStore = VectorStoreService.getInstance(project);
        this.llmService = LLMService.getInstance(project);
        this.configManager = ApplicationManager.getApplication().getService(ConfigManager.class);
    }

    // 获取RAGService实例
    public static RAGService getInstance(Project project) {
        return project.getService(RAGService.class);
    }

    /**
     * 初始化RAG服务，支持智能缓存
     */
    public void initialize() {
        if (isInitialized) {
            return;
        }

        LOG.info("Initializing Improved RAG Service...");

        // 检查是否有缓存的知识库
        if (KnowledgeBaseLoader.cacheExists(PLUGIN_DATA_PATH)) {
            LOG.info("Found cached knowledge base");

            // 加载缓存的条目
            List<KnowledgeEntry> cachedEntries = KnowledgeBaseLoader.loadFromFile(PLUGIN_DATA_PATH);

            if (!cachedEntries.isEmpty()) {
                // 索引缓存的条目
                vectorStore.indexKnowledgeEntries(cachedEntries);
                isInitialized = true;
                LOG.info("Initialized with " + cachedEntries.size() + " cached entries");
                return;
            }
        }

        // 如果没有缓存或者缓存为空，处理文档
        LOG.info("Processing documents to build knowledge base...");
        List<KnowledgeEntry> entries = documentProcessor.processAllDocuments();

        if (entries.isEmpty()) {
            LOG.warn("No documents processed");
            isInitialized = true;
            return;
        }

        // 索引条目
        vectorStore.indexKnowledgeEntries(entries);

        isInitialized = true;
        LOG.info("RAG Service initialized with " + entries.size() + " knowledge entries");
    }

    /**
     * 获取当前编程上下文
     */
    private String getCurrentProgrammingContext() {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            StringBuilder context = new StringBuilder();

            try {
                FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
                Editor selectedEditor = fileEditorManager.getSelectedTextEditor();

                if (selectedEditor != null) {
                    VirtualFile currentFile = fileEditorManager.getSelectedFiles()[0];
                    if (currentFile != null) {
                        context.append("【Current File】").append(currentFile.getName()).append("\n");

                        String fileContent = new String(currentFile.contentsToByteArray());
                        if (fileContent.length() > 1000) {
                            fileContent = fileContent.substring(0, 1000) + "\n... (content truncated)";
                        }
                        context.append("【File Content】\n").append(fileContent).append("\n");

                        int offset = selectedEditor.getCaretModel().getOffset();
                        int lineNumber = selectedEditor.getDocument().getLineNumber(offset);
                        context.append("【Current Line Number】").append(lineNumber + 1).append("\n");
                    }
                }

                VirtualFile[] openFiles = fileEditorManager.getOpenFiles();
                if (openFiles.length > 0) {
                    context.append("【Opened Files】\n");
                    for (int i = 0; i < Math.min(5, openFiles.length); i++) {
                        context.append("- ").append(openFiles[i].getName()).append("\n");
                    }
                }

            } catch (Exception e) {
                LOG.warn("Failed to get programming context: " + e.getMessage());
            }

            return context.toString();
        });
    }

    /**
     * 使用RAG和上下文感知回答问题
     */
    public void answerQuestion(String question, StreamingOutputHandler handler) {
        answerQuestionWithContext(question, null, handler, false);
    }

    /**
     * 回答关于特定代码的问题
     */
    public void answerQuestionAboutCode(String question, String code, StreamingOutputHandler handler) {
        answerQuestionWithContext(question, code, handler, true);
    }

    /**
     * 根据是否有代码上下文来统一处理问题
     */
    public void answerQuestionWithContext(String question, String codeSnippet,
                                          StreamingOutputHandler handler, boolean isCodeQuestion) {
        if (!isInitialized) {
            handler.appendLine("Initializing knowledge base, please wait...\n");
            initialize();
        }

        // 获取当前编程上下文
        String programmingContext = getCurrentProgrammingContext();

        // 搜索相关的知识
        List<KnowledgeEntry> relevantEntries = vectorStore.search(question,
                configManager.getMaxRetrievalResults());

        // 按相似度过滤
        List<KnowledgeEntry> highQualityEntries = filterBySimilarity(relevantEntries, SIMILARITY_THRESHOLD);

        // 构建上下文
        StringBuilder ragContext = new StringBuilder();
        List<String> sources = new ArrayList<>();
        boolean hasRelevantMaterial = !highQualityEntries.isEmpty();

        if (hasRelevantMaterial) {
            LOG.info("Found " + highQualityEntries.size() + " relevant entries");

            // 按来源分组
            Map<String, List<KnowledgeEntry>> entriesBySource = highQualityEntries.stream()
                    .collect(Collectors.groupingBy(KnowledgeEntry::getSource));

            for (Map.Entry<String, List<KnowledgeEntry>> sourceEntry : entriesBySource.entrySet()) {
                String sourceName = sourceEntry.getKey();
                List<KnowledgeEntry> sourceEntries = sourceEntry.getValue();

                // 按页码排序
                sourceEntries.sort(Comparator.comparingInt(KnowledgeEntry::getPage));

                for (KnowledgeEntry entry : sourceEntries) {
                    ragContext.append("【Source: ").append(sourceName)
                            .append(" - Page ").append(entry.getPage()).append("】\n");
                    ragContext.append(entry.getContent()).append("\n\n");

                    String source = sourceName + " (Page " + entry.getPage() + ")";
                    if (!sources.contains(source)) {
                        sources.add(source);
                    }
                }
            }
        } else {
            LOG.info("No relevant entries found above similarity threshold");
        }

        // 构建完整的提示
        Map<String, String> variables = new HashMap<>();
        variables.put("rag_context", ragContext.toString());
        variables.put("programming_context", programmingContext);
        variables.put("question", question);

        // 选择合适的提示模板
        String promptName;
        if (isCodeQuestion && codeSnippet != null) {
            // 关于特定代码的问题
            variables.put("code_snippet", codeSnippet);
            promptName = hasRelevantMaterial ? "qa_code_with_context_prompt" : "qa_code_general_prompt";
        } else {
            // 一般问题（但有编程上下文）
            promptName = hasRelevantMaterial ? "qa_context_aware_prompt" : "qa_general_context_prompt";
        }

        String prompt = PromptLoader.formatPrompt(promptName, variables);

        // 打印发送给LLM的内容以便调试
        LOG.info("=== SENDING TO LLM ===");
        LOG.info("Prompt template: " + promptName);
        LOG.info("Question: " + question);
        if (codeSnippet != null) {
            LOG.info("Code snippet length: " + codeSnippet.length() + " chars");
            LOG.info("Code snippet preview: " +
                    (codeSnippet.length() > 200 ? codeSnippet.substring(0, 200) + "..." : codeSnippet));
        }
        LOG.info("Programming context length: " + programmingContext.length() + " chars");
        LOG.info("RAG context entries: " + highQualityEntries.size());
        LOG.info("Full prompt length: " + prompt.length() + " chars");
        LOG.info("=== END LLM INPUT ===");

        // 生成答案
        llmService.generateStreamingResponse(prompt, new StreamingOutputHandler(null) {
            @Override
            public void appendToken(String token) {
                handler.appendToken(token);
            }
        });

        // 添加来源
        handler.appendLine("\n---\n");
        if (hasRelevantMaterial) {
            for (String source : sources) {
                handler.appendLine("【Reference Source: " + source + "】");
            }
        } else {
            handler.appendLine("【Note: This answer is based on general knowledge, no course materials referenced】");
        }
        handler.appendLine("\n");

    }

    /**
     * 根据相似度阈值过滤条目
     */
    private List<KnowledgeEntry> filterBySimilarity(List<KnowledgeEntry> entries, double threshold) {
        return entries;
    }

    /**
     * 强制重新初始化并使用最新数据
     */
    public void reinitialize() {
        isInitialized = false;

        // 清空所有缓存
        documentProcessor.clearCache();
        vectorStore.clearCache();
        KnowledgeBaseLoader.clearCache(PLUGIN_DATA_PATH);

        // 重新处理所有数据
        initialize();
    }

    /**
     * 获取知识库的统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("initialized", isInitialized);
        stats.put("entryCount", vectorStore.getEntryCount());
        stats.put("cacheExists", KnowledgeBaseLoader.cacheExists(PLUGIN_DATA_PATH));
        stats.put("cacheTimestamp", KnowledgeBaseLoader.getCacheTimestamp(PLUGIN_DATA_PATH));
        return stats;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public int getIndexedChunkCount() {
        return vectorStore.getEntryCount();
    }
}