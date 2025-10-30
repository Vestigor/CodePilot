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
import com.intellij.openapi.project.Project;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Improved RAG Service with persistent knowledge base
 * Inspired by com.tongji.jea
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
     * Answer a question using RAG
     */
    public void answerQuestion(String question, StreamingOutputHandler handler) {
        if (!isInitialized) {
            handler.appendLine("正在初始化知识库，请稍候...\n");
            initialize();
        }

        // Search for relevant knowledge
        List<KnowledgeEntry> relevantEntries = vectorStore.search(question,
                configManager.getMaxRetrievalResults());

        // Filter by similarity threshold
        List<KnowledgeEntry> highQualityEntries = filterBySimilarity(relevantEntries, SIMILARITY_THRESHOLD);

        // Build context from relevant entries
        StringBuilder context = new StringBuilder();
        List<String> sources = new ArrayList<>();
        boolean hasRelevantMaterial = !highQualityEntries.isEmpty();

        if (hasRelevantMaterial) {
            LOG.info("Found " + highQualityEntries.size() + " relevant entries");

            // Group entries by source document
            Map<String, List<KnowledgeEntry>> entriesBySource = highQualityEntries.stream()
                    .collect(Collectors.groupingBy(KnowledgeEntry::getSource));

            for (Map.Entry<String, List<KnowledgeEntry>> sourceEntry : entriesBySource.entrySet()) {
                String sourceName = sourceEntry.getKey();
                List<KnowledgeEntry> sourceEntries = sourceEntry.getValue();

                // Sort by page number
                sourceEntries.sort(Comparator.comparingInt(KnowledgeEntry::getPage));

                for (KnowledgeEntry entry : sourceEntries) {
                    context.append("【来源：").append(sourceName)
                            .append(" - 第").append(entry.getPage()).append("页】\n");
                    context.append(entry.getContent()).append("\n\n");

                    // Collect unique sources
                    String source = sourceName + " (第 " + entry.getPage() + " 页)";
                    if (!sources.contains(source)) {
                        sources.add(source);
                    }
                }
            }
        } else {
            LOG.info("No relevant entries found above similarity threshold");
        }

        // Build prompt
        Map<String, String> variables = new HashMap<>();
        variables.put("context", context.toString());
        variables.put("question", question);

        // Choose prompt template based on context availability
        String promptName = hasRelevantMaterial ? "qa_prompt" : "qa_general_prompt";
        String prompt = PromptLoader.formatPrompt(promptName, variables);

        // Generate answer
        llmService.generateStreamingResponse(prompt, new StreamingOutputHandler(null) {
            @Override
            public void appendToken(String token) {
                handler.appendToken(token);
            }
        });

        // Add sources
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
     * Answer question synchronously
     */
    public ChatMessage answerQuestionSync(String question) {
        if (!isInitialized) {
            initialize();
        }

        // Search for relevant knowledge
        List<KnowledgeEntry> relevantEntries = vectorStore.search(question,
                configManager.getMaxRetrievalResults());

        // Filter by similarity threshold
        List<KnowledgeEntry> highQualityEntries = filterBySimilarity(relevantEntries, SIMILARITY_THRESHOLD);

        // Build context
        StringBuilder context = new StringBuilder();
        List<String> sources = new ArrayList<>();
        boolean hasRelevantMaterial = !highQualityEntries.isEmpty();

        if (hasRelevantMaterial) {
            // Group by source
            Map<String, List<KnowledgeEntry>> entriesBySource = highQualityEntries.stream()
                    .collect(Collectors.groupingBy(KnowledgeEntry::getSource));

            for (Map.Entry<String, List<KnowledgeEntry>> sourceEntry : entriesBySource.entrySet()) {
                String sourceName = sourceEntry.getKey();
                List<KnowledgeEntry> sourceEntries = sourceEntry.getValue();

                // Sort by page
                sourceEntries.sort(Comparator.comparingInt(KnowledgeEntry::getPage));

                for (KnowledgeEntry entry : sourceEntries) {
                    context.append("【来源：").append(sourceName)
                            .append(" - 第").append(entry.getPage()).append("页】\n");
                    context.append(entry.getContent()).append("\n\n");

                    String source = sourceName + " (第 " + entry.getPage() + " 页)";
                    if (!sources.contains(source)) {
                        sources.add(source);
                    }
                }
            }
        }

        // Build prompt and generate answer
        Map<String, String> variables = new HashMap<>();
        variables.put("context", context.toString());
        variables.put("question", question);

        String promptName = hasRelevantMaterial ? "qa_prompt" : "qa_general_prompt";
        String prompt = PromptLoader.formatPrompt(promptName, variables);

        String answer = llmService.generateResponse(prompt);

        ChatMessage response = new ChatMessage("CodePilot", answer);
        if (hasRelevantMaterial) {
            response.setSources(sources);
        }

        return response;
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