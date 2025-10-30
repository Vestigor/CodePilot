package com.codepilot.service;

import com.codepilot.model.ChatMessage;
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

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Improved RAG Service with context awareness
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

    // Similarity thresholds
    private static final double SIMILARITY_THRESHOLD = 0.5;
    private static final double HIGH_SIMILARITY_THRESHOLD = 0.7;

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

    /**
     * Initialize the RAG service with intelligent caching
     */
    public void initialize() {
        if (isInitialized) {
            return;
        }

        LOG.info("Initializing Improved RAG Service...");

        // Check if we have cached knowledge base
        if (KnowledgeBaseLoader.cacheExists(PLUGIN_DATA_PATH)) {
            LOG.info("Found cached knowledge base");

            // Load cached entries
            List<KnowledgeEntry> cachedEntries = KnowledgeBaseLoader.loadFromFile(PLUGIN_DATA_PATH);

            if (!cachedEntries.isEmpty()) {
                // Index cached entries (they should already have embeddings)
                vectorStore.indexKnowledgeEntries(cachedEntries);
                isInitialized = true;
                LOG.info("Initialized with " + cachedEntries.size() + " cached entries");
                return;
            }
        }

        // If no cache or cache is empty, process documents
        LOG.info("Processing documents to build knowledge base...");
        List<KnowledgeEntry> entries = documentProcessor.processAllDocuments();

        if (entries.isEmpty()) {
            LOG.warn("No documents processed");
            isInitialized = true;
            return;
        }

        // Index the entries (will generate embeddings if needed)
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
                        context.append("【当前文件】").append(currentFile.getName()).append("\n");

                        String fileContent = new String(currentFile.contentsToByteArray());
                        if (fileContent.length() > 1000) {
                            fileContent = fileContent.substring(0, 1000) + "\n... (内容已截断)";
                        }
                        context.append("【文件内容】\n").append(fileContent).append("\n");

                        int offset = selectedEditor.getCaretModel().getOffset();
                        int lineNumber = selectedEditor.getDocument().getLineNumber(offset);
                        context.append("【当前行号】").append(lineNumber + 1).append("\n");
                    }
                }

                VirtualFile[] openFiles = fileEditorManager.getOpenFiles();
                if (openFiles.length > 0) {
                    context.append("【打开的文件】\n");
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
     * Answer a question using RAG with context awareness
     */
    public void answerQuestion(String question, StreamingOutputHandler handler) {
        answerQuestionWithContext(question, null, handler, false);
    }

    /**
     * Answer a question about specific code
     */
    public void answerQuestionAboutCode(String question, String code, StreamingOutputHandler handler) {
        answerQuestionWithContext(question, code, handler, true);
    }

    /**
     * Unified method for answering questions with or without code context
     */
    public void answerQuestionWithContext(String question, String codeSnippet,
                                          StreamingOutputHandler handler, boolean isCodeQuestion) {
        if (!isInitialized) {
            handler.appendLine("正在初始化知识库，请稍候...\n");
            initialize();
        }

        // 获取编程上下文
        String programmingContext = getCurrentProgrammingContext();

        // 搜索相关知识
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
                    ragContext.append("【来源：").append(sourceName)
                            .append(" - 第").append(entry.getPage()).append("页】\n");
                    ragContext.append(entry.getContent()).append("\n\n");

                    String source = sourceName + " (第 " + entry.getPage() + " 页)";
                    if (!sources.contains(source)) {
                        sources.add(source);
                    }
                }
            }
        } else {
            LOG.info("No relevant entries found above similarity threshold");
        }

        // 构建完整提示
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
        handler.appendLine("\n\n【参考来源】\n");
        if (hasRelevantMaterial) {
            for (String source : sources) {
                handler.appendLine("- " + source);
            }
        } else {
            handler.appendLine("本回答基于通识知识，未引用课程资料");
        }
        handler.appendLine("\n");
    }

    /**
     * Filter entries by similarity threshold
     */
    private List<KnowledgeEntry> filterBySimilarity(List<KnowledgeEntry> entries, double threshold) {
        // Note: Since KnowledgeEntry doesn't have a similarity field,
        // we would need to modify it or use a wrapper class
        // For now, we'll return all entries assuming they're pre-filtered
        return entries;
    }

    /**
     * Force reinitialization with fresh data
     */
    public void reinitialize() {
        isInitialized = false;

        // Clear all caches
        documentProcessor.clearCache();
        vectorStore.clearCache();
        KnowledgeBaseLoader.clearCache(PLUGIN_DATA_PATH);

        // Reprocess everything
        initialize();
    }

    /**
     * Get statistics about the knowledge base
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