package com.codepilot.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.codepilot.model.DocumentChunk;
import com.codepilot.util.ConfigManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service(Service.Level.PROJECT)
public final class VectorStoreService {
    private static final Logger LOG = Logger.getInstance(VectorStoreService.class);
    private final Project project;
    private final ConfigManager configManager;
    private final List<DocumentChunk> chunks;
    private final Map<String, List<Double>> embeddingCache;
    private TextEmbedding textEmbedding;

    // 相似度阈值常量
    private static final double MIN_SIMILARITY_THRESHOLD = 0.3;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;
    private static final double HIGH_SIMILARITY_THRESHOLD = 0.7;

    public VectorStoreService(Project project) {
        this.project = project;
        this.configManager = ApplicationManager.getApplication().getService(ConfigManager.class);
        this.chunks = new ArrayList<>();
        this.embeddingCache = new ConcurrentHashMap<>();
        initializeEmbeddingService();
    }

    private void initializeEmbeddingService() {
        try {
            textEmbedding = new TextEmbedding();
        } catch (Exception e) {
            LOG.warn("Failed to initialize embedding service, using fallback: " + e.getMessage());
        }
    }

    public static VectorStoreService getInstance(Project project) {
        return project.getService(VectorStoreService.class);
    }

    public void indexChunks(List<DocumentChunk> documentChunks) {
        chunks.clear();
        chunks.addAll(documentChunks);

        // 批量生成嵌入向量
        int batchSize = 25; // 通义千问的批量限制
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            List<DocumentChunk> batch = chunks.subList(i, end);

            try {
                generateBatchEmbeddings(batch);
                LOG.info("Indexed batch " + (i/batchSize + 1) + "/" +
                        ((chunks.size() + batchSize - 1) / batchSize));
            } catch (Exception e) {
                LOG.warn("Failed to generate embeddings for batch, using fallback: " + e.getMessage());
                // 使用备用方法
                for (DocumentChunk chunk : batch) {
                    double[] embedding = generateFallbackEmbedding(chunk.getContent());
                    chunk.setEmbedding(embedding);
                }
            }
        }

        LOG.info("Indexed " + chunks.size() + " chunks with embeddings");
    }

    private void generateBatchEmbeddings(List<DocumentChunk> batch) throws Exception {
        if (textEmbedding == null || configManager.getApiKey() == null ||
                configManager.getApiKey().isEmpty()) {
            throw new Exception("Embedding service not available");
        }

        List<String> texts = batch.stream()
                .map(DocumentChunk::getContent)
                .collect(Collectors.toList());

        TextEmbeddingParam param = TextEmbeddingParam.builder()
                .apiKey(configManager.getApiKey())
                .model("text-embedding-v2") // 使用通义千问的嵌入模型
                .texts(texts)
                .build();

        TextEmbeddingResult result = textEmbedding.call(param);

        List<TextEmbeddingResultItem> embeddings = result.getOutput().getEmbeddings();

        for (int i = 0; i < batch.size() && i < embeddings.size(); i++) {
            List<Double> embeddingList = embeddings.get(i).getEmbedding();
            double[] embeddingArray = embeddingList.stream()
                    .mapToDouble(Double::doubleValue)
                    .toArray();
            batch.get(i).setEmbedding(embeddingArray);

            // 缓存嵌入向量
            embeddingCache.put(batch.get(i).getContent(), embeddingList);
        }
    }

    public List<DocumentChunk> search(String query, int topK) {
        if (chunks.isEmpty()) {
            return new ArrayList<>();
        }

        // 生成查询向量
        double[] queryVector = generateQueryEmbedding(query);

        // 计算相似度并排序
        List<DocumentChunk> scoredChunks = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            double[] chunkVector = chunk.getEmbedding();
            if (chunkVector == null) {
                chunkVector = generateFallbackEmbedding(chunk.getContent());
                chunk.setEmbedding(chunkVector);
            }

            double similarity = cosineSimilarity(queryVector, chunkVector);
            chunk.setSimilarity(similarity);

            // 只添加有最小相关性的块
            if (similarity > MIN_SIMILARITY_THRESHOLD) {
                scoredChunks.add(chunk);
            }
        }

        // 按相似度排序并返回前K个
        return scoredChunks.stream()
                .sorted((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()))
                .limit(Math.min(topK, configManager.getMaxRetrievalResults()))
                .collect(Collectors.toList());
    }

    /**
     * 带有相似度阈值的搜索
     */
    public List<DocumentChunk> searchWithThreshold(String query, int topK, double threshold) {
        List<DocumentChunk> results = search(query, topK);

        // 过滤低于阈值的结果
        return results.stream()
                .filter(chunk -> chunk.getSimilarity() >= threshold)
                .collect(Collectors.toList());
    }

    private double[] generateQueryEmbedding(String query) {
        try {
            if (textEmbedding != null && configManager.getApiKey() != null &&
                    !configManager.getApiKey().isEmpty()) {

                TextEmbeddingParam param = TextEmbeddingParam.builder()
                        .apiKey(configManager.getApiKey())
                        .model("text-embedding-v2")
                        .texts(Arrays.asList(query))
                        .build();

                TextEmbeddingResult result = textEmbedding.call(param);
                List<Double> embedding = result.getOutput().getEmbeddings().get(0).getEmbedding();

                return embedding.stream()
                        .mapToDouble(Double::doubleValue)
                        .toArray();
            }
        } catch (Exception e) {
            LOG.warn("Failed to generate query embedding, using fallback: " + e.getMessage());
        }

        return generateFallbackEmbedding(query);
    }

    private double[] generateFallbackEmbedding(String text) {
        // TF-IDF向量化实现
        String normalizedText = text.toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5\\s]", " ")
                .trim();

        String[] words = normalizedText.split("\\s+");

        // 创建词频向量
        Map<String, Double> tfidf = new HashMap<>();
        Map<String, Integer> wordFreq = new HashMap<>();

        // 计算词频
        for (String word : words) {
            if (word.length() > 1) { // 忽略单字符
                wordFreq.put(word, wordFreq.getOrDefault(word, 0) + 1);
            }
        }

        // 计算TF-IDF
        int totalWords = Math.max(words.length, 1);
        for (Map.Entry<String, Integer> entry : wordFreq.entrySet()) {
            double tf = (double) entry.getValue() / totalWords;
            // 简化的IDF计算
            double idf = Math.log(1 + (double) chunks.size() /
                    (1 + countDocumentsContaining(entry.getKey())));
            tfidf.put(entry.getKey(), tf * idf);
        }

        // 转换为固定长度向量（使用哈希技巧）
        int vectorSize = 1536; // 匹配通义千问嵌入维度
        double[] vector = new double[vectorSize];

        for (Map.Entry<String, Double> entry : tfidf.entrySet()) {
            // 使用多个哈希函数来减少冲突
            int hash1 = Math.abs(entry.getKey().hashCode()) % vectorSize;
            int hash2 = Math.abs(entry.getKey().hashCode() * 31) % vectorSize;
            int hash3 = Math.abs(entry.getKey().hashCode() * 37) % vectorSize;

            double value = entry.getValue();
            vector[hash1] += value;
            vector[hash2] += value * 0.5;
            vector[hash3] += value * 0.25;
        }

        // 归一化
        return normalizeVector(vector);
    }

    private int countDocumentsContaining(String word) {
        int count = 0;
        String lowerWord = word.toLowerCase();
        for (DocumentChunk chunk : chunks) {
            if (chunk.getContent().toLowerCase().contains(lowerWord)) {
                count++;
            }
        }
        return count;
    }

    private double[] normalizeVector(double[] vector) {
        double norm = 0;
        for (double v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);

        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }

        return vector;
    }

    private double cosineSimilarity(double[] vec1, double[] vec2) {
        if (vec1.length != vec2.length) {
            // 如果维度不匹配，调整到相同维度
            int minLen = Math.min(vec1.length, vec2.length);
            vec1 = Arrays.copyOf(vec1, minLen);
            vec2 = Arrays.copyOf(vec2, minLen);
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }

        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }

        double similarity = dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));

        // 确保相似度在[0, 1]范围内
        return Math.max(0.0, Math.min(1.0, similarity));
    }

    /**
     * 获取与查询最相关的块及其相似度
     */
    public Map<String, Double> getTopSimilarities(String query, int topK) {
        List<DocumentChunk> results = search(query, topK);
        Map<String, Double> similarities = new LinkedHashMap<>();

        for (DocumentChunk chunk : results) {
            String key = chunk.getSource() + " - " +
                    chunk.getContent().substring(0, Math.min(50, chunk.getContent().length()));
            similarities.put(key, chunk.getSimilarity());
        }

        return similarities;
    }

    public int getChunkCount() {
        return chunks.size();
    }

    public void clearCache() {
        embeddingCache.clear();
    }
}
