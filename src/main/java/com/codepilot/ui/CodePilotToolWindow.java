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
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

public class CodePilotToolWindow {
    private final Project project;
    private final RAGService ragService;
    private final ConfigManager configManager;

    private JPanel mainPanel;
    private JTextPane chatPane;
    private JTextArea inputArea;
    private JButton sendButton;
    private JButton clearButton;
    private JButton settingsButton;
    private JLabel statusLabel;
    private JScrollPane inputScrollPane;
    private StyledDocument chatDoc;
    private StreamingOutputHandler outputHandler;

    // 输入框高度限制
    private static final int MIN_INPUT_HEIGHT = 60;
    private static final int MAX_INPUT_HEIGHT = 200;
    private static final int LINE_HEIGHT = 20;

    // 样式定义
    private Style studentStyle;
    private Style assistantStyle;
    private Style normalStyle;
    private Style sourceStyle;

    public CodePilotToolWindow(Project project) {
        this.project = project;
        this.ragService = RAGService.getInstance(project);
        this.configManager = ApplicationManager.getApplication().getService(ConfigManager.class);

        initializeUI();
        initializeStyles();
        initializeRAG();
    }

    private void initializeUI() {
        mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBackground(UIUtil.getPanelBackground());
        mainPanel.setBorder(JBUI.Borders.empty());

        // 顶部面板
        JPanel topPanel = createTopPanel();
        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 对话显示区域
        JPanel chatPanel = createChatPanel();
        mainPanel.add(chatPanel, BorderLayout.CENTER);

        // 底部输入区域
        JPanel bottomPanel = createBottomPanel();
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);
    }

    private void initializeStyles() {
        chatDoc = chatPane.getStyledDocument();

        // Student 样式：蓝色、加粗
        studentStyle = chatPane.addStyle("Student", null);
        StyleConstants.setForeground(studentStyle, new JBColor(
                new Color(0x1976D2),
                new Color(0x64B5F6)
        ));
        StyleConstants.setBold(studentStyle, true);
        StyleConstants.setFontSize(studentStyle, 14);

        // CodePilot 样式：绿色、加粗
        assistantStyle = chatPane.addStyle("CodePilot", null);
        StyleConstants.setForeground(assistantStyle, new JBColor(
                new Color(0x388E3C),
                new Color(0x81C784)
        ));
        StyleConstants.setBold(assistantStyle, true);
        StyleConstants.setFontSize(assistantStyle, 14);

        // 普通文本样式
        normalStyle = chatPane.addStyle("Normal", null);
        StyleConstants.setForeground(normalStyle, UIUtil.getLabelForeground());
        StyleConstants.setFontSize(normalStyle, 13);

        // 来源样式：灰色、斜体
        sourceStyle = chatPane.addStyle("Source", null);
        StyleConstants.setForeground(sourceStyle, JBColor.GRAY);
        StyleConstants.setItalic(sourceStyle, true);
        StyleConstants.setFontSize(sourceStyle, 12);
    }

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

        statusLabel = new JBLabel("正在初始化...");
        statusLabel.setFont(JBUI.Fonts.label(11));
        statusLabel.setForeground(JBColor.GRAY);
        leftPanel.add(statusLabel);

        topPanel.add(leftPanel, BorderLayout.WEST);

        // 右侧：操作按钮
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonPanel.setOpaque(false);

        clearButton = createIconButton("🗑", "清空对话");
        clearButton.addActionListener(e -> clearChat());
        buttonPanel.add(clearButton);

        settingsButton = createIconButton("⚙", "设置");
        settingsButton.addActionListener(e -> showSettings());
        buttonPanel.add(settingsButton);

        topPanel.add(buttonPanel, BorderLayout.EAST);

        return topPanel;
    }

    private JPanel createChatPanel() {
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBackground(UIUtil.getPanelBackground());
        chatPanel.setBorder(JBUI.Borders.empty(0, 8, 8, 8));

        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setBackground(UIUtil.getTextFieldBackground());
        chatPane.setMargin(JBUI.insets(12));

        JBScrollPane chatScrollPane = new JBScrollPane(chatPane);
        chatScrollPane.setBorder(createRoundedBorder());
        chatScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        chatScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        chatPanel.add(chatScrollPane, BorderLayout.CENTER);

        return chatPanel;
    }

    private JPanel createBottomPanel() {
        JPanel bottomPanel = new JPanel(new BorderLayout(0, 8));
        bottomPanel.setBackground(UIUtil.getPanelBackground());
        bottomPanel.setBorder(JBUI.Borders.empty(8, 16, 16, 16));

        // 输入区域容器
        JPanel inputContainer = new JPanel(new BorderLayout(8, 0));
        inputContainer.setOpaque(false);

        // 输入框
        inputArea = new JTextArea(3, 20);
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
                        // Shift+Enter 允许换行，使用默认行为
                    } else {
                        // Enter 发送
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
        sendButton.setText("发送");
        sendButton.setFont(JBUI.Fonts.label(13).asBold());
        sendButton.setPreferredSize(new Dimension(70, 60));
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

        // 提示文本
        JBLabel hintLabel = new JBLabel("💡 提示: Enter 发送 | Shift+Enter 换行");
        hintLabel.setFont(JBUI.Fonts.label(11));
        hintLabel.setForeground(JBColor.GRAY);
        bottomPanel.add(hintLabel, BorderLayout.SOUTH);

        return bottomPanel;
    }

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
                        Math.max(MIN_INPUT_HEIGHT, lines * LINE_HEIGHT + 20)
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

    private void initializeRAG() {
        statusLabel.setText("正在初始化知识库...");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                ragService.initialize();
                return null;
            }

            @Override
            protected void done() {
                int chunkCount = ragService.getIndexedChunkCount();
                statusLabel.setText("已就绪 · " + chunkCount + " 个知识块");
                appendWelcomeMessage(chunkCount);
            }
        };
        worker.execute();
    }

    private void appendWelcomeMessage(int chunkCount) {
        try {
            chatDoc.insertString(chatDoc.getLength(), "👋 欢迎使用 CodePilot\n\n", normalStyle);
            chatDoc.insertString(chatDoc.getLength(),
                    "我是您的 Java 企业应用开发课程教学助手。\n", normalStyle);
            chatDoc.insertString(chatDoc.getLength(),
                    "知识库已加载 " + chunkCount + " 个知识块，随时为您解答课程相关问题。\n\n", normalStyle);
            chatDoc.insertString(chatDoc.getLength(),
                    "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n", sourceStyle);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private void sendMessage() {
        String question = inputArea.getText().trim();
        if (question.isEmpty()) {
            return;
        }

        // 显示用户问题
        appendUserMessage(question);
        inputArea.setText("");

        // 重置输入框高度
        inputScrollPane.setPreferredSize(new Dimension(0, MIN_INPUT_HEIGHT));
        inputScrollPane.revalidate();

        // 禁用输入
        setInputEnabled(false);
        statusLabel.setText("正在思考...");

        // 显示助手标签
        appendAssistantHeader();

        // 创建特殊的输出处理器用于富文本
        StreamingOutputHandler handler = new StreamingOutputHandler(null) {
            @Override
            public void appendToken(String token) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        chatDoc.insertString(chatDoc.getLength(), token, normalStyle);
                        chatPane.setCaretPosition(chatDoc.getLength());
                    } catch (BadLocationException e) {
                        e.printStackTrace();
                    }
                });
            }

            @Override
            public void appendLine(String line) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        // 检测是否是来源信息
                        if (line.contains("【参考来源】") || line.startsWith("- ") ||
                                line.contains("本回答基于通识知识")) {
                            chatDoc.insertString(chatDoc.getLength(), line + "\n", sourceStyle);
                        } else {
                            chatDoc.insertString(chatDoc.getLength(), line + "\n", normalStyle);
                        }
                        chatPane.setCaretPosition(chatDoc.getLength());
                    } catch (BadLocationException e) {
                        e.printStackTrace();
                    }
                });
            }
        };

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                ragService.answerQuestion(question, handler);
                return null;
            }

            @Override
            protected void done() {
                setInputEnabled(true);
                statusLabel.setText("已就绪 · " + ragService.getIndexedChunkCount() + " 个知识块");
                inputArea.requestFocus();

                // 添加分隔线
                try {
                    chatDoc.insertString(chatDoc.getLength(),
                            "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n", sourceStyle);
                } catch (BadLocationException e) {
                    e.printStackTrace();
                }
            }
        };
        worker.execute();
    }

    private void appendUserMessage(String content) {
        try {
            chatDoc.insertString(chatDoc.getLength(), "Student: ", studentStyle);
            chatDoc.insertString(chatDoc.getLength(), content + "\n\n", normalStyle);
            chatPane.setCaretPosition(chatDoc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private void appendAssistantHeader() {
        try {
            chatDoc.insertString(chatDoc.getLength(), "CodePilot: ", assistantStyle);
            chatPane.setCaretPosition(chatDoc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private void setInputEnabled(boolean enabled) {
        sendButton.setEnabled(enabled);
        inputArea.setEnabled(enabled);
        clearButton.setEnabled(enabled);
    }

    private void clearChat() {
        int result = Messages.showYesNoDialog(
                project,
                "确定要清空所有对话记录吗？",
                "清空对话",
                "清空",
                "取消",
                Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            chatPane.setText("");
            appendWelcomeMessage(ragService.getIndexedChunkCount());
        }
    }

    public void appendToInput(String text) {
        String currentText = inputArea.getText();
        if (!currentText.isEmpty() && !currentText.endsWith("\n")) {
            inputArea.append("\n");
        }
        inputArea.append(text);
        adjustInputHeight();
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
