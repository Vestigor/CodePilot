package com.codepilot.ui;

import com.codepilot.service.RAGService;
import com.codepilot.util.ConfigManager;
import com.codepilot.util.StreamingOutputHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * 增强的 CodePilot 工具窗口，支持 Markdown 渲染
 */
public class CodePilotToolWindow {
    // 输入框高度限制常量
    private static final int MIN_INPUT_HEIGHT = 40;
    private static final int MAX_INPUT_HEIGHT = 120;
    private static final int LINE_HEIGHT = 20;

    // 服务相关
    private final Project project;
    private final RAGService ragService;
    private final ConfigManager configManager;
    private final MarkdownRenderer markdownRenderer;

    // UI 组件
    private JPanel mainPanel;
    private JTextArea inputArea;
    private JButton sendButton;
    private JButton clearButton;
    private JButton settingsButton;
    private JLabel statusLabel;
    private JScrollPane inputScrollPane;
    private StreamingOutputHandler outputHandler;

    // 状态变量 - 用于跟踪当前正在流式传输的消息
    private StringBuilder currentAssistantMessage;
    private boolean isStreaming = false;

    public CodePilotToolWindow(Project project) {
        this.project = project;
        this.ragService = RAGService.getInstance(project);
        this.configManager = ApplicationManager.getApplication().getService(ConfigManager.class);
        this.markdownRenderer = new MarkdownRenderer();
        this.currentAssistantMessage = new StringBuilder();

        initializeUI();
        initializeRAG();
    }

    /**
     * 获取主面板内容
     */
    public JPanel getContent() {
        return mainPanel;
    }

    /**
     * 直接发送代码相关问题
     */
    public void sendCodeQuestion(String question, String code) {
        // 显示用户问题和代码
        String messageWithCode = question + "\n\n```java\n" + code + "\n```";
        markdownRenderer.renderMarkdown(messageWithCode, MarkdownRenderer.MessageType.STUDENT);

        // 处理问题
        processQuestion(question, code, true);
    }

    /**
     * 追加文本到输入框
     */
    public void appendToInput(String text) {
        String currentText = inputArea.getText();
        if (!currentText.isEmpty() && !currentText.endsWith("\n")) {
            inputArea.append("\n");
        }
        inputArea.append(text);
        adjustInputHeight();
        inputArea.requestFocus();
    }

    /**
     * 初始化用户界面
     */
    private void initializeUI() {
        mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBackground(UIUtil.getPanelBackground());
        mainPanel.setBorder(JBUI.Borders.empty());

        // 顶部面板
        JPanel topPanel = createTopPanel();
        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 使用 Markdown 渲染器的聊天显示区域
        JPanel chatPanel = createChatPanel();
        mainPanel.add(chatPanel, BorderLayout.CENTER);

        // 底部输入区域
        JPanel bottomPanel = createBottomPanel();
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);
    }

    /**
     * 创建顶部面板
     */
    private JPanel createTopPanel() {
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(UIUtil.getPanelBackground());
        topPanel.setBorder(JBUI.Borders.empty(12, 16, 8, 16));

        // 左侧：标题和状态
        JPanel leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
        leftPanel.setOpaque(false);

        JBLabel titleLabel = new JBLabel("CodePilot");
        titleLabel.setFont(JBUI.Fonts.label(18).asBold());
        titleLabel.setForeground(new JBColor(
                new Color(0x2C5282),
                new Color(0x63B3ED)
        ));
        leftPanel.add(titleLabel);

        leftPanel.add(Box.createVerticalStrut(4));

        statusLabel = new JBLabel("Initializing...");
        statusLabel.setFont(JBUI.Fonts.label(11));
        statusLabel.setForeground(JBColor.GRAY);
        leftPanel.add(statusLabel);

        topPanel.add(leftPanel, BorderLayout.WEST);

        // 右侧：操作按钮
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonPanel.setOpaque(false);

        clearButton = createIconButton("🗑", "Clear conversation");
        clearButton.addActionListener(e -> clearChat());
        buttonPanel.add(clearButton);

        settingsButton = createIconButton("⚙", "Settings");
        settingsButton.addActionListener(e -> showSettings());
        buttonPanel.add(settingsButton);

        topPanel.add(buttonPanel, BorderLayout.EAST);

        return topPanel;
    }

    /**
     * 创建聊天显示面板
     */
    private JPanel createChatPanel() {
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBackground(UIUtil.getPanelBackground());
        chatPanel.setBorder(JBUI.Borders.empty(0, 8, 8, 8));

        // 使用 Markdown 渲染器的组件
        JComponent markdownComponent = markdownRenderer.getComponent();
        if (markdownComponent instanceof JScrollPane) {
            JScrollPane scrollPane = (JScrollPane) markdownComponent;
            scrollPane.setBorder(createRoundedBorder());
            scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
            scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        }

        chatPanel.add(markdownComponent, BorderLayout.CENTER);

        return chatPanel;
    }

    /**
     * 创建底部输入面板
     */
    private JPanel createBottomPanel() {
        JPanel bottomPanel = new JPanel(new BorderLayout(0, 8));
        bottomPanel.setBackground(UIUtil.getPanelBackground());
        bottomPanel.setBorder(JBUI.Borders.empty(8, 16, 16, 16));

        // 输入区域容器
        JPanel inputContainer = new JPanel(new BorderLayout(8, 0));
        inputContainer.setOpaque(false);

        // 输入文本区域
        inputArea = new JTextArea(1, 20);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(JBUI.Fonts.label(13));
        inputArea.setMargin(JBUI.insets(10));
        inputArea.setBackground(UIUtil.getTextFieldBackground());

        // 监听文本变化以调整高度
        inputArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                adjustInputHeight();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                adjustInputHeight();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                adjustInputHeight();
            }
        });

        // Enter 发送，Shift+Enter 换行
        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown()) {
                        // Shift+Enter 添加新行
                        inputArea.append("\n");
                        adjustInputHeight();
                        e.consume();
                    } else {
                        // Enter 发送消息
                        sendMessage();
                        e.consume();
                    }
                }
            }
        });

        inputScrollPane = new JBScrollPane(inputArea);
        inputScrollPane.setBorder(createRoundedBorder());
        inputScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        inputScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScrollPane.setPreferredSize(new Dimension(0, MIN_INPUT_HEIGHT));

        inputContainer.add(inputScrollPane, BorderLayout.CENTER);

        // 发送按钮
        sendButton = new JButton();
        sendButton.setText("Send");
        sendButton.setFont(JBUI.Fonts.label(13).asBold());
        sendButton.setPreferredSize(new Dimension(70, 40));
        sendButton.setFocusPainted(false);
        sendButton.setBackground(new JBColor(
                new Color(0x4A90E2),
                new Color(0x2D5F8F)
        ));
        sendButton.setForeground(Color.WHITE);
        sendButton.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
        sendButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendButton.addActionListener(e -> sendMessage());

        // 鼠标悬停效果
        sendButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                sendButton.setBackground(new JBColor(
                        new Color(0x357ABD),
                        new Color(0x3D7AB8)
                ));
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent evt) {
                sendButton.setBackground(new JBColor(
                        new Color(0x4A90E2),
                        new Color(0x2D5F8F)
                ));
            }
        });

        inputContainer.add(sendButton, BorderLayout.EAST);

        bottomPanel.add(inputContainer, BorderLayout.CENTER);

        // 提示标签
        JBLabel hintLabel = new JBLabel("💡 Tip: Enter to send | Shift+Enter for new line");
        hintLabel.setFont(JBUI.Fonts.label(11));
        hintLabel.setForeground(JBColor.GRAY);
        bottomPanel.add(hintLabel, BorderLayout.SOUTH);

        return bottomPanel;
    }

    /**
     * 创建图标按钮
     */
    private JButton createIconButton(String icon, String tooltip) {
        JButton button = new JButton(icon);
        button.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 16));
        button.setPreferredSize(new Dimension(36, 36));
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // 悬停效果
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                button.setContentAreaFilled(true);
                button.setBackground(new JBColor(
                        new Color(0, 0, 0, 10),
                        new Color(255, 255, 255, 10)
                ));
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent evt) {
                button.setContentAreaFilled(false);
            }
        });

        return button;
    }

    /**
     * 创建圆角边框
     */
    private Border createRoundedBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new JBColor(
                        new Color(0xD0D0D0),
                        new Color(0x3C3F41)
                ), 1, true),
                JBUI.Borders.empty(2)
        );
    }

    /**
     * 根据内容自动调整输入框高度
     */
    private void adjustInputHeight() {
        SwingUtilities.invokeLater(() -> {
            try {
                // 计算文本所需的行数
                FontMetrics fm = inputArea.getFontMetrics(inputArea.getFont());
                int textWidth = inputScrollPane.getViewport().getWidth() - 20;
                int lines = 1;

                String text = inputArea.getText();
                if (!text.isEmpty()) {
                    String[] paragraphs = text.split("\n", -1);
                    for (String paragraph : paragraphs) {
                        if (paragraph.isEmpty()) {
                            lines++;
                        } else {
                            int width = fm.stringWidth(paragraph);
                            lines += Math.max(1, (width + textWidth - 1) / textWidth);
                        }
                    }
                }

                // 计算高度
                int height = Math.min(
                        MAX_INPUT_HEIGHT,
                        Math.max(MIN_INPUT_HEIGHT, lines * LINE_HEIGHT)
                );

                Dimension newSize = new Dimension(inputScrollPane.getWidth(), height);
                if (!inputScrollPane.getPreferredSize().equals(newSize)) {
                    inputScrollPane.setPreferredSize(newSize);
                    inputScrollPane.revalidate();
                    inputScrollPane.getParent().revalidate();
                }
            } catch (Exception e) {
                // 忽略计算错误
            }
        });
    }

    /**
     * 初始化 RAG 服务
     */
    private void initializeRAG() {
        statusLabel.setText("Initializing knowledge base...");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                ragService.initialize();
                return null;
            }

            @Override
            protected void done() {
                int chunkCount = ragService.getIndexedChunkCount();
                statusLabel.setText("Ready · " + chunkCount + " knowledge chunks");
                appendWelcomeMessage(chunkCount);
            }
        };
        worker.execute();
    }

    /**
     * 追加欢迎消息
     */
    private void appendWelcomeMessage(int chunkCount) {
        String welcomeMessage = """
            # 👋 Welcome to CodePilot
            
            I am your teaching assistant for **Java Enterprise Application Development** course.
            
            Knowledge base loaded with **%d knowledge chunks**, ready to answer course-related questions.
            
            *Tip: Right-click selected code to ask related questions*
            
            ---
            """.formatted(chunkCount);

        markdownRenderer.renderMarkdown(welcomeMessage, MarkdownRenderer.MessageType.SYSTEM);
    }

    /**
     * 发送消息
     */
    private void sendMessage() {
        String question = inputArea.getText().trim();
        if (question.isEmpty() || isStreaming) {
            return;
        }

        // 显示用户问题
        markdownRenderer.renderMarkdown(question, MarkdownRenderer.MessageType.STUDENT);
        inputArea.setText("");

        // 重置输入框高度
        inputScrollPane.setPreferredSize(new Dimension(0, MIN_INPUT_HEIGHT));
        inputScrollPane.revalidate();

        // 处理问题
        processQuestion(question, null, false);
    }

    /**
     * 处理问题
     */
    private void processQuestion(String question, String code, boolean isCodeQuestion) {
        // 禁用输入
        setInputEnabled(false);
        statusLabel.setText("Thinking...");
        isStreaming = true;
        currentAssistantMessage.setLength(0);

        // 开始时显示助手标签
        markdownRenderer.renderMarkdown("", MarkdownRenderer.MessageType.ASSISTANT);

        // 为 Markdown 创建流式输出处理器
        StreamingOutputHandler handler = new StreamingOutputHandler(null) {
            private final StringBuilder buffer = new StringBuilder();
            private int lastRenderedLength = 0; // 记录上次渲染的长度

            @Override
            public void appendToken(String token) {
                SwingUtilities.invokeLater(() -> {
                    buffer.append(token);
                    currentAssistantMessage.append(token);

                    // 使用增量渲染而不是每次都清除重渲染
                    if (currentAssistantMessage.length() - lastRenderedLength > 50) {
                        // 每累积 50 个字符更新一次，减少渲染频率，防止卡顿
                        updateAssistantMessage();
                        lastRenderedLength = currentAssistantMessage.length();
                    }
                });
            }

            @Override
            public void appendLine(String line) {
                appendToken(line + "\n");
            }

            private void updateAssistantMessage() {
                // 移除旧的助手消息并添加新的
                markdownRenderer.updateLastMessage(
                        currentAssistantMessage.toString(),
                        MarkdownRenderer.MessageType.ASSISTANT
                );
            }
        };

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                if (isCodeQuestion && code != null) {
                    ragService.answerQuestionAboutCode(question, code, handler);
                } else {
                    ragService.answerQuestion(question, handler);
                }
                return null;
            }

            @Override
            protected void done() {
                // 最后一次更新，确保所有内容都显示
                SwingUtilities.invokeLater(() -> {
                    markdownRenderer.updateLastMessage(
                            currentAssistantMessage.toString(),
                            MarkdownRenderer.MessageType.ASSISTANT
                    );
                });

                setInputEnabled(true);
                statusLabel.setText("Ready · " + ragService.getIndexedChunkCount() + " knowledge chunks");
                inputArea.requestFocus();
                isStreaming = false;

                // 添加分隔符
                markdownRenderer.addSeparator();
            }
        };
        worker.execute();
    }

    /**
     * 设置输入是否启用
     */
    private void setInputEnabled(boolean enabled) {
        sendButton.setEnabled(enabled);
        inputArea.setEnabled(enabled);
        clearButton.setEnabled(enabled);
    }

    /**
     * 清除聊天记录
     */
    private void clearChat() {
        int result = Messages.showYesNoDialog(
                project,
                "Are you sure you want to clear all conversation records?",
                "Clear Conversation",
                "Clear",
                "Cancel",
                Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            markdownRenderer.clear();
            currentAssistantMessage.setLength(0);
            appendWelcomeMessage(ragService.getIndexedChunkCount());
        }
    }

    /**
     * 显示设置对话框
     */
    private void showSettings() {
        SettingsDialog dialog = new SettingsDialog(project);
        dialog.show();
    }
}