package com.codepilot.ui;

import com.codepilot.model.ModelConfig;
import com.codepilot.util.ConfigManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class SettingsDialog extends DialogWrapper {
    private final ConfigManager configManager;
    private JTextField apiKeyField;
    private JComboBox<String> modelComboBox;

    public SettingsDialog(Project project) {
        super(project);
        this.configManager = ApplicationManager.getApplication().getService(ConfigManager.class);
        setTitle("CodePilot Settings");
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(10));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(5);

        // API Key
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.3;
        panel.add(new JLabel("API Key:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.7;
        apiKeyField = new JTextField(30);
        apiKeyField.setText(configManager.getUserApiKey());
        panel.add(apiKeyField, gbc);

        // 模型选择
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.3;
        panel.add(new JLabel("Model:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.7;
        List<ModelConfig> models = configManager.getModels();
        String[] modelNames = models.stream()
                .map(ModelConfig::getDisplayName)
                .toArray(String[]::new);
        modelComboBox = new JComboBox<>(modelNames);

        String selectedModel = configManager.getSelectedModel();
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).getName().equals(selectedModel)) {
                modelComboBox.setSelectedIndex(i);
                break;
            }
        }
        panel.add(modelComboBox, gbc);

        // 说明文本
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        JLabel noteLabel = new JLabel("<html><i>Your API key will be securely stored</i></html>");
        noteLabel.setForeground(Color.GRAY);
        panel.add(noteLabel, gbc);

        return panel;
    }

    @Override
    protected void doOKAction() {
        String apiKey = apiKeyField.getText().trim();
        configManager.setUserApiKey(apiKey);

        List<ModelConfig> models = configManager.getModels();
        int selectedIndex = modelComboBox.getSelectedIndex();
        if (selectedIndex >= 0 && selectedIndex < models.size()) {
            configManager.setSelectedModel(models.get(selectedIndex).getName());
        }

        super.doOKAction();
    }
}