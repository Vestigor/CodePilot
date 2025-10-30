package com.codepilot.ui;

import com.codepilot.model.ChatMessage;
import com.codepilot.service.RAGService;
import com.codepilot.util.ConfigManager;
import com.codepilot.util.StreamingOutputHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

public class CodePilotToolWindow {
    private final Project project;
    private final RAGService ragService;
    private final ConfigManager configManager;

    private JPanel mainPanel;
    private JTextArea chatArea;
    private JTextArea inputArea;
    private JButton sendButton;
    private JButton settingsButton;
    private StreamingOutputHandler outputHandler;

    public CodePilotToolWindow(Project project) {
        this.project = project;
        this.ragService = RAGService.getInstance(project);
        this.configManager = ApplicationManager.getApplication().getService(ConfigManager.class);

        initializeUI();
        initializeRAG();
    }

    private void initializeUI() {
        mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBorder(JBUI.Borders.empty());

        // 顶部面板
        JPanel topPanel = createTopPanel();
        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 对话显示区域
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        chatArea.setMargin(JBUI.insets(10));

        JScrollPane chatScrollPane = new JScrollPane(chatArea);
        chatScrollPane.setBorder(JBUI.Borders.empty());
        mainPanel.add(chatScrollPane, BorderLayout.CENTER);

        // 底部输入区域
        JPanel bottomPanel = createBottomPanel();
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        outputHandler = new StreamingOutputHandler(chatArea);
    }

    private JPanel createTopPanel() {
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(JBUI.Borders.empty(10, 10, 5, 10));

        JLabel titleLabel = new JLabel("CodePilot");
        titleLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
        topPanel.add(titleLabel, BorderLayout.WEST);

        settingsButton = new JButton("⚙");
        settingsButton.setPreferredSize(new Dimension(40, 30));
        settingsButton.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 16));
        settingsButton.addActionListener(e -> showSettings());
        topPanel.add(settingsButton, BorderLayout.EAST);

        return topPanel;
    }

    private JPanel createBottomPanel() {
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 0));
        bottomPanel.setBorder(JBUI.Borders.empty(5, 10, 10, 10));

        // 输入框
        inputArea = new JTextArea(3, 20);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && e.isControlDown()) {
                    sendMessage();
                    e.consume();
                }
            }
        });

        JScrollPane inputScrollPane = new JScrollPane(inputArea);
        inputScrollPane.setBorder(BorderFactory.createLineBorder(JBColor.GRAY));
        bottomPanel.add(inputScrollPane, BorderLayout.CENTER);

        // 发送按钮
        sendButton = new JButton("✈");
        sendButton.setPreferredSize(new Dimension(50, 60));
        sendButton.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
        sendButton.addActionListener(e -> sendMessage());
        bottomPanel.add(sendButton, BorderLayout.EAST);

        return bottomPanel;
    }

    private void initializeRAG() {
        chatArea.append("正在初始化知识库，请稍候...\n\n");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                ragService.initialize();
                return null;
            }

            @Override
            protected void done() {
                chatArea.setText("");
                chatArea.append("CodePilot：您好！我是Java企业应用开发课程的教学助手。\n");
                chatArea.append("知识库已加载 " + ragService.getIndexedChunkCount() + " 个知识块。\n");
                chatArea.append("您可以向我提问任何与课程相关的问题。\n\n\n");
            }
        };
        worker.execute();
    }

    private void sendMessage() {
        String question = inputArea.getText().trim();
        if (question.isEmpty()) {
            return;
        }

        // 显示用户问题
        chatArea.append("Student：" + question + "\n\n");
        inputArea.setText("");

        // 禁用输入
        sendButton.setEnabled(false);
        inputArea.setEnabled(false);

        // 准备接收回答
        chatArea.append("CodePilot：");
        outputHandler.reset();

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                ragService.answerQuestion(question, outputHandler);
                return null;
            }

            @Override
            protected void done() {
                sendButton.setEnabled(true);
                inputArea.setEnabled(true);
                inputArea.requestFocus();
            }
        };
        worker.execute();
    }

    public void appendToInput(String text) {
        String currentText = inputArea.getText();
        if (!currentText.isEmpty() && !currentText.endsWith("\n")) {
            inputArea.append("\n");
        }
        inputArea.append(text);
        inputArea.requestFocus();
    }

    private void showSettings() {
        SettingsDialog dialog = new SettingsDialog(project);
        dialog.show();
    }

    public JPanel getContent() {
        return mainPanel;
    }
}
