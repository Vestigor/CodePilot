package com.codepilot.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.codepilot.model.KnowledgeEntry;
import com.codepilot.util.ConfigManager;
import com.codepilot.util.KnowledgeBaseLoader;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Improved Vector Store Service with persistent embeddings
 * Inspired by com.tongji.jea's embedding approach
 */
@Service(Service.Level.PROJECT)
public final class VectorStoreService {
    private static final Logger LOG = Logger.getInstance(VectorStoreService.class);
    private final Project project;
    private final ConfigManager configManager;
    private final List<KnowledgeEntry> knowledgeEntries;
    private final Map<String, List<Double>> embeddingCache;
    private TextEmbedding textEmbedding;
    private static final String PLUGIN_DATA_PATH = PathManager.getPluginsPath() + "/CodePilot/data";

    // Similarity thresholds
    private static final double MIN_SIMILARITY_THRESHOLD = 0.3;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;
    private static final double HIGH_SIMILARITY_THRESHOLD = 0.7;

    public VectorStoreService(Project project) {
        this.project = project;
        this.configManager = ApplicationManager.getApplication().getService(ConfigManager.class);
        this.knowledgeEntries = new ArrayList<>();
        this.embeddingCache = new ConcurrentHashMap<>();
        initializeEmbeddingService();
    }

    private void initializeEmbeddingService() {
        try {
            textEmbedding = new TextEmbedding();
            LOG.info("Text embedding service initialized");
        } catch (Exception e) {
            LOG.warn("Failed to initialize embedding service: " + e.getMessage());
        }
    }

    public static VectorStoreService getInstance(Project project) {
        return project.getService(VectorStoreService.class);
    }

    /**
     * Index knowledge entries with embeddings
     * If entries already have embeddings, use them; otherwise generate new ones
     */
    public void indexKnowledgeEntries(List<KnowledgeEntry> entries) {
        knowledgeEntries.clear();
        knowledgeEntries.addAll(entries);

        // Check which entries need embeddings
        List<KnowledgeEntry> entriesNeedingEmbeddings = entries.stream()
                .filter(e -> e.getEmbedding() == null || e.getEmbedding().isEmpty())
                .collect(Collectors.toList());

        if (entriesNeedingEmbeddings.isEmpty()) {
            LOG.info("All " + entries.size() + " entries already have embeddings");
            return;
        }

        LOG.info("Generating embeddings for " + entriesNeedingEmbeddings.size() + " entries");

        // Generate embeddings in batches
        int batchSize = 25; // Dashscope limit
        for (int i = 0; i < entriesNeedingEmbeddings.size(); i += batchSize) {
            int end = Math.min(i + batchSize, entriesNeedingEmbeddings.size());
            List<KnowledgeEntry> batch = entriesNeedingEmbeddings.subList(i, end);

            try {
                generateBatchEmbeddings(batch);
                LOG.info("Generated embeddings for batch " + (i/batchSize + 1));
            } catch (Exception e) {
                LOG.warn("Failed to generate embeddings for batch, using fallback: " + e.getMessage());
                // Use fallback for this batch
                for (KnowledgeEntry entry : batch) {
                    List<Double> embedding = generateFallbackEmbedding(entry.getContent());
                    entry.setEmbedding(embedding);
                }
            }
        }

        // Save updated entries with embeddings
        KnowledgeBaseLoader.saveToFile(knowledgeEntries, PLUGIN_DATA_PATH);
        LOG.info("Indexed and saved " + knowledgeEntries.size() + " knowledge entries");
    }

    /**
     * Generate embeddings for a batch of entries
     */
    private void generateBatchEmbeddings(List<KnowledgeEntry> batch) throws Exception {
        if (textEmbedding == null || configManager.getApiKey() == null ||
                configManager.getApiKey().isEmpty()) {
            throw new Exception("Embedding service not available");
        }

        List<String> texts = batch.stream()
                .map(KnowledgeEntry::getContent)
                .collect(Collectors.toList());

        TextEmbeddingParam param = TextEmbeddingParam.builder()
                .apiKey(configManager.getApiKey())
                .model(configManager.getCurrentModelConfig().getName().equals("text-embedding-v2")
                        ? "text-embedding-v2" : "text-embedding-v2")
                .texts(texts)
                .build();

        TextEmbeddingResult result = textEmbedding.call(param);
        List<TextEmbeddingResultItem> embeddings = result.getOutput().getEmbeddings();

        for (int i = 0; i < batch.size() && i < embeddings.size(); i++) {
            List<Double> embedding = embeddings.get(i).getEmbedding();
            batch.get(i).setEmbedding(embedding);

            // Cache the embedding
            embeddingCache.put(batch.get(i).getChunkId(), embedding);
        }
    }

    /**
     * Search for relevant knowledge entries
     */
    public List<KnowledgeEntry> search(String query, int topK) {
        if (knowledgeEntries.isEmpty()) {
            LOG.warn("No knowledge entries to search");
            return new ArrayList<>();
        }

        // Generate query embedding
        List<Double> queryEmbedding = generateQueryEmbedding(query);

        // Calculate similarities and create result entries
        List<KnowledgeSearchResult> searchResults = new ArrayList<>();

        for (KnowledgeEntry entry : knowledgeEntries) {
            List<Double> entryEmbedding = entry.getEmbedding();

            if (entryEmbedding == null || entryEmbedding.isEmpty()) {
                LOG.warn("Entry " + entry.getChunkId() + " has no embedding");
                continue;
            }

            double similarity = cosineSimilarity(queryEmbedding, entryEmbedding);

            // Only include results above minimum threshold
            if (similarity > MIN_SIMILARITY_THRESHOLD) {
                searchResults.add(new KnowledgeSearchResult(entry, similarity));
            }
        }

        // Sort by similarity and return top K
        return searchResults.stream()
                .sorted((a, b) -> Double.compare(b.similarity, a.similarity))
                .limit(Math.min(topK, configManager.getMaxRetrievalResults()))
                .map(result -> {
                    // Create a copy with similarity score
                    KnowledgeEntry entry = new KnowledgeEntry(
                            result.entry.getChunkId(),
                            result.entry.getContent(),
                            result.entry.getSource(),
                            result.entry.getPage(),
                            result.entry.getDocumentType()
                    );
                    entry.setEmbedding(result.entry.getEmbedding());
                    // Store similarity in a transient field or as part of the entry
                    return entry;
                })
                .collect(Collectors.toList());
    }

    /**
     * Generate embedding for query text
     */
    private List<Double> generateQueryEmbedding(String query) {
        try {
            // Check cache first
            if (embeddingCache.containsKey(query)) {
                return embeddingCache.get(query);
            }

            if (textEmbedding != null && configManager.getApiKey() != null &&
                    !configManager.getApiKey().isEmpty()) {

                TextEmbeddingParam param = TextEmbeddingParam.builder()
                        .apiKey(configManager.getApiKey())
                        .model("text-embedding-v2")
                        .texts(Arrays.asList(query))
                        .build();

                TextEmbeddingResult result = textEmbedding.call(param);
                List<Double> embedding = result.getOutput().getEmbeddings().get(0).getEmbedding();

                // Cache the result
                embeddingCache.put(query, embedding);

                return embedding;
            }
        } catch (Exception e) {
            LOG.warn("Failed to generate query embedding, using fallback: " + e.getMessage());
        }

        return generateFallbackEmbedding(query);
    }

    /**
     * Fallback embedding generation using TF-IDF
     */
    private List<Double> generateFallbackEmbedding(String text) {
        // Simple TF-IDF based embedding
        String normalizedText = text.toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5\\s]", " ")
                .trim();

        String[] words = normalizedText.split("\\s+");

        // Create word frequency map
        Map<String, Double> tfidf = new HashMap<>();
        Map<String, Integer> wordFreq = new HashMap<>();

        for (String word : words) {
            if (word.length() > 1) {
                wordFreq.put(word, wordFreq.getOrDefault(word, 0) + 1);
            }
        }

        // Calculate TF-IDF
        int totalWords = Math.max(words.length, 1);
        for (Map.Entry<String, Integer> entry : wordFreq.entrySet()) {
            double tf = (double) entry.getValue() / totalWords;
            double idf = Math.log(1 + (double) knowledgeEntries.size() /
                    (1 + countDocumentsContaining(entry.getKey())));
            tfidf.put(entry.getKey(), tf * idf);
        }

        // Convert to fixed-length vector using hash trick
        int vectorSize = 1536; // Match OpenAI/DashScope embedding size
        List<Double> vector = new ArrayList<>(Collections.nCopies(vectorSize, 0.0));

        for (Map.Entry<String, Double> entry : tfidf.entrySet()) {
            int hash1 = Math.abs(entry.getKey().hashCode()) % vectorSize;
            int hash2 = Math.abs(entry.getKey().hashCode() * 31) % vectorSize;
            int hash3 = Math.abs(entry.getKey().hashCode() * 37) % vectorSize;

            double value = entry.getValue();
            vector.set(hash1, vector.get(hash1) + value);
            vector.set(hash2, vector.get(hash2) + value * 0.5);
            vector.set(hash3, vector.get(hash3) + value * 0.25);
        }

        // Normalize
        return normalizeVector(vector);
    }

    /**
     * Count documents containing a word
     */
    private int countDocumentsContaining(String word) {
        String lowerWord = word.toLowerCase();
        return (int) knowledgeEntries.stream()
                .filter(entry -> entry.getContent().toLowerCase().contains(lowerWord))
                .count();
    }

    /**
     * Normalize vector
     */
    private List<Double> normalizeVector(List<Double> vector) {
        double norm = 0;
        for (Double v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);

        if (norm > 0) {
            for (int i = 0; i < vector.size(); i++) {
                vector.set(i, vector.get(i) / norm);
            }
        }

        return vector;
    }

    /**
     * Calculate cosine similarity between two embeddings
     */
    private double cosineSimilarity(List<Double> vec1, List<Double> vec2) {
        if (vec1.size() != vec2.size()) {
            LOG.warn("Vector size mismatch: " + vec1.size() + " vs " + vec2.size());
            int minSize = Math.min(vec1.size(), vec2.size());
            vec1 = vec1.subList(0, minSize);
            vec2 = vec2.subList(0, minSize);
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vec1.size(); i++) {
            double v1 = vec1.get(i);
            double v2 = vec2.get(i);
            dotProduct += v1 * v2;
            norm1 += v1 * v1;
            norm2 += v2 * v2;
        }

        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }

        double similarity = dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));

        // Ensure similarity is in [0, 1]
        return Math.max(0.0, Math.min(1.0, similarity));
    }

    /**
     * Get the number of indexed entries
     */
    public int getEntryCount() {
        return knowledgeEntries.size();
    }

    /**
     * Clear all caches
     */
    public void clearCache() {
        embeddingCache.clear();
        knowledgeEntries.clear();
    }

    /**
     * Force reindex all entries
     */
    public void reindexAll() {
        clearCache();
        // Will need to reload from document processor
    }

    /**
     * Internal class for search results
     */
    private static class KnowledgeSearchResult {
        final KnowledgeEntry entry;
        final double similarity;

        KnowledgeSearchResult(KnowledgeEntry entry, double similarity) {
            this.entry = entry;
            this.similarity = similarity;
        }
    }
}
