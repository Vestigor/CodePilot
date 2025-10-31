package com.codepilot.util;

import com.codepilot.model.ModelConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.*;
import java.util.List;

@Service(Service.Level.APP)
public final class ConfigManager {
    private static final Logger LOG = Logger.getInstance(ConfigManager.class);
    private static final String CONFIG_FILE = "config/config.json";
    private JsonObject config;
    private final Gson gson;

    public ConfigManager() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadConfig();
    }

    private void loadConfig() {
        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE);
            if (is == null) {
                LOG.error("Config file not found: " + CONFIG_FILE);
                config = new JsonObject();
                return;
            }

            Reader reader = new InputStreamReader(is);
            config = gson.fromJson(reader, JsonObject.class);
            reader.close();

            // 尝试加载用户配置覆盖
            File userConfig = getUserConfigFile();
            if (userConfig.exists()) {
                Reader userReader = new FileReader(userConfig);
                JsonObject userConfigObj = gson.fromJson(userReader, JsonObject.class);
                userReader.close();

                // 合并配置
                if (userConfigObj.has("userApiKey")) {
                    config.addProperty("userApiKey",
                            userConfigObj.get("userApiKey").getAsString());
                }
                if (userConfigObj.has("selectedModel")) {
                    config.addProperty("selectedModel",
                            userConfigObj.get("selectedModel").getAsString());
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to load config", e);
            config = new JsonObject();
        }
    }

    public void saveConfig() {
        try {
            File userConfig = getUserConfigFile();
            userConfig.getParentFile().mkdirs();

            JsonObject saveConfig = new JsonObject();
            saveConfig.addProperty("userApiKey", getUserApiKey());
            saveConfig.addProperty("selectedModel", getSelectedModel());

            Writer writer = new FileWriter(userConfig);
            gson.toJson(saveConfig, writer);
            writer.close();
        } catch (Exception e) {
            LOG.error("Failed to save config", e);
        }
    }

    private File getUserConfigFile() {
        String pluginPath = PathManager.getPluginsPath();
        return new File(pluginPath, "CodePilot/config.json");
    }

    public String getApiKey() {
        String userKey = getUserApiKey();
        if (userKey != null && !userKey.isEmpty()) {
            return userKey;
        }
        return getDefaultApiKey();
    }

    public String getUserApiKey() {
        return config.has("userApiKey") ?
                config.get("userApiKey").getAsString() : "";
    }

    public void setUserApiKey(String apiKey) {
        config.addProperty("userApiKey", apiKey);
        saveConfig();
    }

    public String getDefaultApiKey() {
        return config.has("defaultApiKey") ?
                config.get("defaultApiKey").getAsString() : "";
    }

    public String getSelectedModel() {
        return config.has("selectedModel") ?
                config.get("selectedModel").getAsString() : "qwen-plus";
    }

    public void setSelectedModel(String model) {
        config.addProperty("selectedModel", model);
        saveConfig();
    }

    public List<ModelConfig> getModels() {
        return gson.fromJson(config.get("models"),
                new TypeToken<List<ModelConfig>>(){}.getType());
    }

    public ModelConfig getCurrentModelConfig() {
        String selectedModel = getSelectedModel();
        return getModels().stream()
                .filter(m -> m.getName().equals(selectedModel))
                .findFirst()
                .orElse(getModels().get(0));
    }

    public String getCourseMaterialsPath() {
        return config.has("courseMaterialsPath") ?
                config.get("courseMaterialsPath").getAsString() : "course_materials";
    }

    public int getChunkSize() {
        return config.has("chunkSize") ?
                config.get("chunkSize").getAsInt() : 500;
    }

    public int getChunkOverlap() {
        return config.has("chunkOverlap") ?
                config.get("chunkOverlap").getAsInt() : 50;
    }

    public int getMaxRetrievalResults() {
        return config.has("maxRetrievalResults") ?
                config.get("maxRetrievalResults").getAsInt() : 3;
    }
}