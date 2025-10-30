package com.codepilot.service;

import com.codepilot.model.DocumentChunk;
import com.codepilot.util.ConfigManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.*;
import java.util.stream.Collectors;

@Service(Service.Level.PROJECT)
public final class VectorStoreService {
    private static final Logger LOG = Logger.getInstance(VectorStoreService.class);
    private final Project project;
    private final ConfigManager configManager;
    private final List<DocumentChunk> chunks;
    private final Map<String, double[]> embeddingCache;

    public VectorStoreService(Project project) {
        this.project = project;
        this.configManager = ApplicationManager.getApplication().getService(ConfigManager.class);
        this.chunks = new ArrayList<>();
        this.embeddingCache = new HashMap<>();
    }

    public static VectorStoreService getInstance(Project project) {
        return project.getService(VectorStoreService.class);
    }

    public void indexChunks(List<DocumentChunk> documentChunks) {
        chunks.clear();
        chunks.addAll(documentChunks);
        LOG.info("Indexed " + chunks.size() + " chunks");
    }

    public List<DocumentChunk> search(String query, int topK) {
        if (chunks.isEmpty()) {
            return new ArrayList<>();
        }

        double[] queryVector = getEmbedding(query);

        for (DocumentChunk chunk : chunks) {
            double[] chunkVector = getEmbedding(chunk.getContent());
            double similarity = cosineSimilarity(queryVector, chunkVector);
            chunk.setSimilarity(similarity);
        }

        return chunks.stream()
                .sorted((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()))
                .limit(Math.min(topK, configManager.getMaxRetrievalResults()))
                .collect(Collectors.toList());
    }

    private double[] getEmbedding(String text) {
        if (embeddingCache.containsKey(text)) {
            return embeddingCache.get(text);
        }

        String[] words = text.toLowerCase().split("\\s+");
        Map<String, Integer> wordFreq = new HashMap<>();

        for (String word : words) {
            wordFreq.put(word, wordFreq.getOrDefault(word, 0) + 1);
        }

        double[] vector = new double[100];
        int index = 0;
        for (Map.Entry<String, Integer> entry : wordFreq.entrySet()) {
            if (index >= vector.length) break;
            vector[index++] = entry.getValue();
        }

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

        embeddingCache.put(text, vector);
        return vector;
    }

    private double cosineSimilarity(double[] vec1, double[] vec2) {
        if (vec1.length != vec2.length) {
            return 0.0;
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

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    public int getChunkCount() {
        return chunks.size();
    }
}
