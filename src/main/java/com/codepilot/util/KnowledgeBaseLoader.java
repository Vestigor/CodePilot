package com.codepilot.util;

import com.codepilot.model.KnowledgeEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for loading and saving knowledge base to/from JSON
 */
public class KnowledgeBaseLoader {
    private static final Logger LOG = Logger.getInstance(KnowledgeBaseLoader.class);
    private static final String KNOWLEDGE_BASE_FILE = "knowledge_base_with_embeddings.json";
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Load knowledge base from JSON file in the plugin's data directory
     */
    public static List<KnowledgeEntry> loadFromFile(String pluginDataPath) {
        Path knowledgeBasePath = Paths.get(pluginDataPath, KNOWLEDGE_BASE_FILE);

        if (!Files.exists(knowledgeBasePath)) {
            LOG.info("Knowledge base file not found at: " + knowledgeBasePath);
            return new ArrayList<>();
        }

        try {
            String content = Files.readString(knowledgeBasePath, StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<KnowledgeEntry>>() {}.getType();
            List<KnowledgeEntry> entries = gson.fromJson(content, listType);

            LOG.info("Loaded " + entries.size() + " knowledge entries from cache");
            return entries;

        } catch (IOException e) {
            LOG.error("Failed to load knowledge base from file", e);
            return new ArrayList<>();
        }
    }

    /**
     * Save knowledge base to JSON file
     */
    public static void saveToFile(List<KnowledgeEntry> entries, String pluginDataPath) {
        Path knowledgeBasePath = Paths.get(pluginDataPath, KNOWLEDGE_BASE_FILE);

        try {
            // Ensure directory exists
            Files.createDirectories(knowledgeBasePath.getParent());

            // Save to JSON
            String json = gson.toJson(entries);
            Files.writeString(knowledgeBasePath, json, StandardCharsets.UTF_8);

            LOG.info("Saved " + entries.size() + " knowledge entries to: " + knowledgeBasePath);

        } catch (IOException e) {
            LOG.error("Failed to save knowledge base to file", e);
        }
    }

    /**
     * Check if cached knowledge base exists
     */
    public static boolean cacheExists(String pluginDataPath) {
        Path knowledgeBasePath = Paths.get(pluginDataPath, KNOWLEDGE_BASE_FILE);
        return Files.exists(knowledgeBasePath);
    }

    /**
     * Get the timestamp of the cached knowledge base
     */
    public static long getCacheTimestamp(String pluginDataPath) {
        Path knowledgeBasePath = Paths.get(pluginDataPath, KNOWLEDGE_BASE_FILE);

        if (!Files.exists(knowledgeBasePath)) {
            return 0;
        }

        try {
            return Files.getLastModifiedTime(knowledgeBasePath).toMillis();
        } catch (IOException e) {
            LOG.error("Failed to get cache timestamp", e);
            return 0;
        }
    }

    /**
     * Clear the cached knowledge base
     */
    public static void clearCache(String pluginDataPath) {
        Path knowledgeBasePath = Paths.get(pluginDataPath, KNOWLEDGE_BASE_FILE);

        try {
            if (Files.exists(knowledgeBasePath)) {
                Files.delete(knowledgeBasePath);
                LOG.info("Cleared knowledge base cache");
            }
        } catch (IOException e) {
            LOG.error("Failed to clear cache", e);
        }
    }
}