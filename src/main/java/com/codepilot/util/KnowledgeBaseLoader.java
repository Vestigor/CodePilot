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
 * 用于从 JSON 加载和保存知识库
 */
public class KnowledgeBaseLoader {
    private static final Logger LOG = Logger.getInstance(KnowledgeBaseLoader.class);
    private static final String KNOWLEDGE_BASE_FILE = "knowledge_base_with_embeddings.json";
    private static final String RESOURCE_KNOWLEDGE_BASE_PATH = "knowledge_base/" + KNOWLEDGE_BASE_FILE;
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 从插件的数据目录中加载 JSON 文件形式的知识库
     * 优先从 src/main/resources/knowledge_base/ 目录加载，如果不存在则从插件数据目录加载
     */
    public static List<KnowledgeEntry> loadFromFile(String pluginDataPath) {
        // 首先尝试从资源目录加载
        List<KnowledgeEntry> entries = loadFromResources();
        if (!entries.isEmpty()) {
            return entries;
        }

        // 如果资源目录没有找到，从插件数据目录加载
        Path knowledgeBasePath = Paths.get(pluginDataPath, KNOWLEDGE_BASE_FILE);

        if (!Files.exists(knowledgeBasePath)) {
            LOG.info("Knowledge base file not found at: " + knowledgeBasePath);
            return new ArrayList<>();
        }

        try {
            String content = Files.readString(knowledgeBasePath, StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<KnowledgeEntry>>() {}.getType();
            entries = gson.fromJson(content, listType);

            LOG.info("Loaded " + entries.size() + " knowledge entries from cache at: " + knowledgeBasePath);
            return entries;

        } catch (IOException e) {
            LOG.error("Failed to load knowledge base from file", e);
            return new ArrayList<>();
        }
    }

    /**
     * 从资源目录加载知识库
     */
    private static List<KnowledgeEntry> loadFromResources() {
        try {
            // 尝试从 resources/knowledge_base 目录加载
            InputStream is = KnowledgeBaseLoader.class.getClassLoader()
                    .getResourceAsStream(RESOURCE_KNOWLEDGE_BASE_PATH);

            if (is == null) {
                LOG.debug("Knowledge base not found in resources: " + RESOURCE_KNOWLEDGE_BASE_PATH);
                return new ArrayList<>();
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();

            Type listType = new TypeToken<List<KnowledgeEntry>>() {}.getType();
            List<KnowledgeEntry> entries = gson.fromJson(content.toString(), listType);

            LOG.info("Loaded " + entries.size() + " knowledge entries from resources: " + RESOURCE_KNOWLEDGE_BASE_PATH);
            return entries;

        } catch (Exception e) {
            LOG.debug("Failed to load knowledge base from resources: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 将知识库保存为 JSON 文件
     */
    public static void saveToFile(List<KnowledgeEntry> entries, String pluginDataPath) {
        Path knowledgeBasePath = Paths.get(pluginDataPath, KNOWLEDGE_BASE_FILE);

        try {
            // 确保目录存在
            Files.createDirectories(knowledgeBasePath.getParent());

            // 保存为 JSON 文件
            String json = gson.toJson(entries);
            Files.writeString(knowledgeBasePath, json, StandardCharsets.UTF_8);

            LOG.info("Saved " + entries.size() + " knowledge entries to: " + knowledgeBasePath);

        } catch (IOException e) {
            LOG.error("Failed to save knowledge base to file", e);
        }
    }

    /**
     * 检查是否存在缓存的知识库
     * 优先检查资源目录，然后检查插件数据目录
     */
    public static boolean cacheExists(String pluginDataPath) {
        // 先检查资源目录
        try {
            InputStream is = KnowledgeBaseLoader.class.getClassLoader()
                    .getResourceAsStream(RESOURCE_KNOWLEDGE_BASE_PATH);
            if (is != null) {
                is.close();
                return true;
            }
        } catch (IOException e) {
            LOG.debug("Error checking resource cache: " + e.getMessage());
        }

        // 再检查插件数据目录
        Path knowledgeBasePath = Paths.get(pluginDataPath, KNOWLEDGE_BASE_FILE);
        return Files.exists(knowledgeBasePath);
    }

    /**
     * 获取缓存知识库的时间戳
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
     * 清除缓存的知识库
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