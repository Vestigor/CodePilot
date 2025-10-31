package com.codepilot.util;

import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class PromptLoader {
    private static final Logger LOG = Logger.getInstance(PromptLoader.class);
    private static final Map<String, String> promptCache = new HashMap<>();

    private static final String PROMPTS_PATH = "prompts/";

    public static String loadPrompt(String promptName) {
        if (promptCache.containsKey(promptName)) {
            return promptCache.get(promptName);
        }

        try {
            String path = PROMPTS_PATH + promptName + ".txt";
            InputStream is = PromptLoader.class.getClassLoader().getResourceAsStream(path);
            if (is == null) {
                LOG.error("Prompt file not found: " + path);
                return "";
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();

            String prompt = content.toString();
            promptCache.put(promptName, prompt);
            return prompt;
        } catch (IOException e) {
            LOG.error("Failed to load prompt: " + promptName, e);
            return "";
        }
    }

    public static String formatPrompt(String promptName, Map<String, String> variables) {
        String template = loadPrompt(promptName);
        String result = template;

        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }

        return result;
    }
}