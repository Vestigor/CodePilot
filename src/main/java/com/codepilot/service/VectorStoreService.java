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
 * 向量存储服务，带有持久化嵌入
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

    // 相似度阈值
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

    // 初始化文本嵌入服务
    private void initializeEmbeddingService() {
        try {
            textEmbedding = new TextEmbedding();
            LOG.info("Text embedding service initialized"); // 输出嵌入服务初始化信息
        } catch (Exception e) {
            LOG.warn("Failed to initialize embedding service: " + e.getMessage()); // 输出错误信息
        }
    }

    // 获取 VectorStoreService 实例
    public static VectorStoreService getInstance(Project project) {
        return project.getService(VectorStoreService.class);
    }

    /**
     * 创建索引知识条目并生成嵌入
     * 如果条目已经有嵌入，则使用它们；否则生成新的嵌入
     */
    public void indexKnowledgeEntries(List<KnowledgeEntry> entries) {
        knowledgeEntries.clear();
        knowledgeEntries.addAll(entries);

        // 检查哪些条目需要生成嵌入
        List<KnowledgeEntry> entriesNeedingEmbeddings = entries.stream()
                .filter(e -> e.getEmbedding() == null || e.getEmbedding().isEmpty())
                .collect(Collectors.toList());

        if (entriesNeedingEmbeddings.isEmpty()) {
            LOG.info("All " + entries.size() + " entries already have embeddings"); // 输出信息，所有条目已经有嵌入
            return;
        }

        LOG.info("Generating embeddings for " + entriesNeedingEmbeddings.size() + " entries"); // 输出生成嵌入的条目数

        // 批量生成嵌入
        int batchSize = 25; // Dashscope 限制
        for (int i = 0; i < entriesNeedingEmbeddings.size(); i += batchSize) {
            int end = Math.min(i + batchSize, entriesNeedingEmbeddings.size());
            List<KnowledgeEntry> batch = entriesNeedingEmbeddings.subList(i, end);

            try {
                generateBatchEmbeddings(batch);
                LOG.info("Generated embeddings for batch " + (i/batchSize + 1)); // 输出当前批次的生成情况
            } catch (Exception e) {
                LOG.warn("Failed to generate embeddings for batch, using fallback: " + e.getMessage()); // 输出错误信息
                // 使用备选方案为这批条目生成嵌入
                for (KnowledgeEntry entry : batch) {
                    List<Double> embedding = generateFallbackEmbedding(entry.getContent());
                    entry.setEmbedding(embedding);
                }
            }
        }

        // 保存带有嵌入的条目
        KnowledgeBaseLoader.saveToFile(knowledgeEntries, PLUGIN_DATA_PATH);
        LOG.info("Indexed and saved " + knowledgeEntries.size() + " knowledge entries"); // 输出索引和保存的条目数
    }

    /**
     * 为一批条目生成嵌入
     */
    private void generateBatchEmbeddings(List<KnowledgeEntry> batch) throws Exception {
        if (textEmbedding == null || configManager.getApiKey() == null ||
                configManager.getApiKey().isEmpty()) {
            throw new Exception("Embedding service not available"); // 输出服务不可用的错误
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

            // 缓存嵌入
            embeddingCache.put(batch.get(i).getChunkId(), embedding);
        }
    }

    /**
     * 搜索相关的知识条目
     */
    public List<KnowledgeEntry> search(String query, int topK) {
        if (knowledgeEntries.isEmpty()) {
            LOG.warn("No knowledge entries to search"); // 输出没有可用的条目进行搜索
            return new ArrayList<>();
        }

        // 生成查询嵌入
        List<Double> queryEmbedding = generateQueryEmbedding(query);

        // 计算相似度并创建结果条目
        List<KnowledgeSearchResult> searchResults = new ArrayList<>();

        for (KnowledgeEntry entry : knowledgeEntries) {
            List<Double> entryEmbedding = entry.getEmbedding();

            if (entryEmbedding == null || entryEmbedding.isEmpty()) {
                LOG.warn("Entry " + entry.getChunkId() + " has no embedding"); // 输出条目缺少嵌入的警告
                continue;
            }

            double similarity = cosineSimilarity(queryEmbedding, entryEmbedding);

            // 只包括相似度大于最小阈值的结果
            if (similarity > MIN_SIMILARITY_THRESHOLD) {
                searchResults.add(new KnowledgeSearchResult(entry, similarity));
            }
        }

        // 按相似度排序并返回前K个结果
        return searchResults.stream()
                .sorted((a, b) -> Double.compare(b.similarity, a.similarity))
                .limit(Math.min(topK, configManager.getMaxRetrievalResults()))
                .map(result -> {
                    // 创建带有相似度分数的条目副本
                    KnowledgeEntry entry = new KnowledgeEntry(
                            result.entry.getChunkId(),
                            result.entry.getContent(),
                            result.entry.getSource(),
                            result.entry.getPage(),
                            result.entry.getDocumentType()
                    );
                    entry.setEmbedding(result.entry.getEmbedding());
                    // 可以将相似度存储在临时字段中
                    return entry;
                })
                .collect(Collectors.toList());
    }

    /**
     * 为查询文本生成嵌入
     */
    private List<Double> generateQueryEmbedding(String query) {
        try {
            // 首先检查缓存
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

                // 缓存查询结果
                embeddingCache.put(query, embedding);

                return embedding;
            }
        } catch (Exception e) {
            LOG.warn("Failed to generate query embedding, using fallback: " + e.getMessage()); // 输出错误信息
        }

        return generateFallbackEmbedding(query); // 使用备选方案生成查询嵌入
    }

    /**
     * 使用TF-IDF生成备选嵌入
     */
    private List<Double> generateFallbackEmbedding(String text) {
        // 简单的基于TF-IDF的嵌入
        String normalizedText = text.toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5\\s]", " ")
                .trim();

        String[] words = normalizedText.split("\\s+");

        // 创建单词频率映射
        Map<String, Double> tfidf = new HashMap<>();
        Map<String, Integer> wordFreq = new HashMap<>();

        for (String word : words) {
            if (word.length() > 1) {
                wordFreq.put(word, wordFreq.getOrDefault(word, 0) + 1);
            }
        }

        // 计算TF-IDF
        int totalWords = Math.max(words.length, 1);
        for (Map.Entry<String, Integer> entry : wordFreq.entrySet()) {
            double tf = (double) entry.getValue() / totalWords;
            double idf = Math.log(1 + (double) knowledgeEntries.size() /
                    (1 + countDocumentsContaining(entry.getKey())));
            tfidf.put(entry.getKey(), tf * idf);
        }

        // 使用哈希技巧转换为固定长度的向量
        int vectorSize = 1536; // 与OpenAI/DashScope嵌入大小匹配
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

        // 归一化
        return normalizeVector(vector);
    }

    /**
     * 计算包含某个单词的文档数量
     */
    private int countDocumentsContaining(String word) {
        String lowerWord = word.toLowerCase();
        return (int) knowledgeEntries.stream()
                .filter(entry -> entry.getContent().toLowerCase().contains(lowerWord))
                .count();
    }

    /**
     * 归一化向量
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
     * 计算两个嵌入之间的余弦相似度
     */
    private double cosineSimilarity(List<Double> vec1, List<Double> vec2) {
        if (vec1.size() != vec2.size()) {
            LOG.warn("Vector size mismatch: " + vec1.size() + " vs " + vec2.size()); // 输出向量大小不匹配的警告
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

        // 确保相似度在[0, 1]之间
        return Math.max(0.0, Math.min(1.0, similarity));
    }

    /**
     * 获取已索引条目的数量
     */
    public int getEntryCount() {
        return knowledgeEntries.size();
    }

    /**
     * 清除所有缓存
     */
    public void clearCache() {
        embeddingCache.clear();
        knowledgeEntries.clear();
    }

    /**
     * 强制重新索引所有条目
     */
    public void reindexAll() {
        clearCache();
    }

    /**
     * 内部类：搜索结果
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