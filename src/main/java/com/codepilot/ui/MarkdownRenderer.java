package com.codepilot.ui;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import java.awt.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CodePilot 聊天界面的 Markdown 渲染器
 * 支持基本的 Markdown 语法，包括代码块、列表、粗体、斜体、表格等
 */
public class MarkdownRenderer {
    private static final Logger LOG = Logger.getInstance(MarkdownRenderer.class);

    // Markdown 模式匹配
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```([\\w]*)?\\n?([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("\\*([^*]+)\\*");
    private static final Pattern HEADING1_PATTERN = Pattern.compile("^# (.+)$", Pattern.MULTILINE);
    private static final Pattern HEADING2_PATTERN = Pattern.compile("^## (.+)$", Pattern.MULTILINE);
    private static final Pattern HEADING3_PATTERN = Pattern.compile("^### (.+)$", Pattern.MULTILINE);
    private static final Pattern HEADING4_PATTERN = Pattern.compile("^#### (.+)$", Pattern.MULTILINE);
    private static final Pattern HEADING5_PATTERN = Pattern.compile("^##### (.+)$", Pattern.MULTILINE);
    private static final Pattern HR_PATTERN = Pattern.compile("^---+\\s*$", Pattern.MULTILINE);
    private static final Pattern LIST_PATTERN = Pattern.compile("^[\\*\\-\\+] (.+)$", Pattern.MULTILINE);
    private static final Pattern NUMBERED_LIST_PATTERN = Pattern.compile("^\\d+\\. (.+)$", Pattern.MULTILINE);
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^\\)]+)\\)");
    private static final Pattern REFERENCE_SOURCE_PATTERN = Pattern.compile("【([^】]+)】");
    private static final Pattern TABLE_ROW_LINE = Pattern.compile("^\\s*[|\\uFF5C].*[|\\uFF5C]\\s*$");
    private static final Pattern TABLE_SEP_LINE = Pattern.compile("^\\s*[|\\uFF5C]?(\\s*:?-{3,}:?\\s*[|\\uFF5C])+\\s*$");

    private final JEditorPane editorPane;
    private final HTMLDocument document;
    private final HTMLEditorKit htmlKit;
    private final StringBuilder htmlContent;
    private final String inlineStyles; // 存储样式，不放入 htmlContent
    private int lastMessageStartIndex = 0; // 记录最后一条消息的起始位置

    public MarkdownRenderer() {
        this.editorPane = new JEditorPane();
        this.editorPane.setContentType("text/html");
        this.editorPane.setEditable(false);
        this.editorPane.setBackground(UIUtil.getTextFieldBackground());

        this.htmlKit = new HTMLEditorKit();
        editorPane.setEditorKit(htmlKit);
        this.document = (HTMLDocument) editorPane.getDocument();

        this.htmlContent = new StringBuilder();
        this.inlineStyles = generateInlineStyles();
    }

    /**
     * 将 Markdown 内容渲染为 HTML 并显示
     */
    public void renderMarkdown(String content, MessageType type) {
        try {
            // 记录当前位置作为新消息的起始位置
            lastMessageStartIndex = htmlContent.length();

            String html = convertMarkdownToHtml(content, type);
            htmlContent.append(html);
            editorPane.setText(wrapInHtml(htmlContent.toString()));

            // 滚动到底部
            SwingUtilities.invokeLater(() -> {
                editorPane.setCaretPosition(editorPane.getDocument().getLength());
            });
        } catch (Exception e) {
            LOG.error("Failed to render markdown", e);
            // 降级到纯文本
            appendPlainText(content, type);
        }
    }

    /**
     * 更新最后一条消息
     */
    public void updateLastMessage(String content, MessageType type) {
        try {
            // 移除最后一条消息
            if (lastMessageStartIndex > 0 && lastMessageStartIndex < htmlContent.length()) {
                htmlContent.setLength(lastMessageStartIndex);
            }

            // 添加新内容
            String html = convertMarkdownToHtml(content, type);
            htmlContent.append(html);
            editorPane.setText(wrapInHtml(htmlContent.toString()));

            // 自动滚动
            SwingUtilities.invokeLater(() -> {
                editorPane.setCaretPosition(editorPane.getDocument().getLength());
            });
        } catch (Exception e) {
            LOG.error("Failed to update last message", e);
        }
    }

    /**
     * 清除所有内容
     */
    public void clear() {
        htmlContent.setLength(0);
        editorPane.setText("");
    }

    /**
     * 添加分隔线
     */
    public void addSeparator() {
        htmlContent.append("<div class='separator'></div>");
        editorPane.setText(wrapInHtml(htmlContent.toString()));
    }

    /**
     * 获取 JEditorPane 组件
     */
    public JComponent getComponent() {
        return new JScrollPane(editorPane);
    }

    /**
     * 生成内联样式
     */
    private String generateInlineStyles() {
        Color textColor = UIUtil.getLabelForeground();
        Color bgColor = UIUtil.getTextFieldBackground();
        Color codeBlockBg = new JBColor(new Color(245, 245, 245), new Color(45, 45, 45));
        Color inlineCodeBg = new JBColor(new Color(240, 240, 240), new Color(50, 50, 50));
        Color tableHeaderBg = new JBColor(new Color(230, 230, 230), new Color(60, 60, 60));  // 表头背景色
        Color tableHeaderText = new JBColor(Color.BLACK, Color.WHITE);
        return String.format("""
        body {
            font-family: %s;
            font-size: 10px;
            color: %s;
            background-color: %s;
            padding: 12px;
        }
        
        .student-label {
            color: #1976D2;
            font-weight: bold;
            font-size: 11px;
            margin-bottom: 8px;
        }
        
        .assistant-label {
            color: #388E3C;
            font-weight: bold;
            font-size: 11px;
            margin-bottom: 8px;
        }
        
        .code-block {
            font-family: monospace;
            font-size: 10px;
            background-color: %s;
            border: 1px solid #ddd;
            padding: 10px;
            margin: 10px 0;
        }
        
        .inline-code {
            font-family: monospace;
            font-size: 10px;
            background-color: %s;
            padding: 2px 4px;
        }
        
        h1 {
            font-size: 14px;
            font-weight: bold;
            margin: 16px 0 8px 0;
            color: %s;
        }
        
        h2 {
            font-size: 13px;
            font-weight: bold;
            margin: 14px 0 6px 0;
            color: %s;
        }
        
        h3 {
            font-size: 12px;
            font-weight: bold;
            margin: 12px 0 4px 0;
            color: %s;
        }
        
        h4 {
            font-size: 11px;
            font-weight: bold;
            margin: 10px 0 4px 0;
            color: %s;
        }
        
        h5 {
            font-size: 10px;
            font-weight: bold;
            margin: 8px 0 4px 0;
            color: %s;
        }
        
        hr {
            border: none;
            border-top: 1px solid #ccc;
            margin: 16px 0;
        }
        
        ul, ol {
            margin: 8px 0;
            padding-left: 20px;
        }
        
        li {
            margin: 4px 0;
        }
        
        table {
            border-collapse: collapse;
            margin: 10px 0;
        }
        
        th {
            background-color: %s;
            font-weight: bold;
            padding: 8px 12px;
            border: 1px solid #ddd;
            text-align: left;
            color: %s;
        }
        
        td {
            padding: 8px 12px;
            border: 1px solid #ddd;
        }
        
        .reference-source {
            color: #666;
            font-style: italic;
            font-size: 9px;
            margin: 8px 0;
        }
        
        .separator {
            border-top: 1px solid #ccc;
            margin: 16px 0;
        }
        
        a {
            color: #1976D2;
            text-decoration: none;
        }
        
        blockquote {
            border-left: 4px solid #ddd;
            padding-left: 12px;
            margin-left: 0;
            color: #666;
        }
        """,
                "Dialog, sans-serif",
                toHexString(textColor),
                toHexString(bgColor),
                toHexString(codeBlockBg),
                toHexString(inlineCodeBg),
                toHexString(textColor),
                toHexString(textColor),
                toHexString(textColor),
                toHexString(textColor),
                toHexString(textColor),
                toHexString(tableHeaderBg),
                toHexString(tableHeaderText)
        );
    }

    /**
     * 将颜色转换为十六进制字符串
     */
    private String toHexString(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /**
     * 将 Markdown 转换为 HTML
     */
    private String convertMarkdownToHtml(String markdown, MessageType type) {
        StringBuilder html = new StringBuilder();

        // 添加消息类型标签
        if (type == MessageType.STUDENT) {
            html.append("<div class='student-label'>Student:</div>");
        } else if (type == MessageType.ASSISTANT) {
            html.append("<div class='assistant-label'>CodePilot:</div>");
        }

        // 处理 Markdown
        String processed = markdown;

        // 首先处理代码块（保护它们不被其他处理影响）
        StringBuffer codeBlockResult = new StringBuffer();
        Matcher codeBlockMatcher = CODE_BLOCK_PATTERN.matcher(processed);
        while (codeBlockMatcher.find()) {
            String language = codeBlockMatcher.group(1);
            String code = escapeHtml(codeBlockMatcher.group(2));
            String replacement = String.format("<div class='code-block'>%s</div>", code);
            codeBlockMatcher.appendReplacement(codeBlockResult, Matcher.quoteReplacement(replacement));
        }
        codeBlockMatcher.appendTail(codeBlockResult);
        processed = codeBlockResult.toString();

        // 处理行内代码
        processed = INLINE_CODE_PATTERN.matcher(processed).replaceAll(
                "<span class='inline-code'>$1</span>"
        );

        // 处理水平分隔符（必须在标题之前以避免冲突）
        processed = HR_PATTERN.matcher(processed).replaceAll("<hr>");

        // 处理标题（从 h5 到 h1，避免 h1 匹配 ## 或 ###）
        processed = HEADING5_PATTERN.matcher(processed).replaceAll("<h5>$1</h5>");
        processed = HEADING4_PATTERN.matcher(processed).replaceAll("<h4>$1</h4>");
        processed = HEADING3_PATTERN.matcher(processed).replaceAll("<h3>$1</h3>");
        processed = HEADING2_PATTERN.matcher(processed).replaceAll("<h2>$1</h2>");
        processed = HEADING1_PATTERN.matcher(processed).replaceAll("<h1>$1</h1>");

        // 处理粗体和斜体
        processed = BOLD_PATTERN.matcher(processed).replaceAll("<strong>$1</strong>");
        processed = ITALIC_PATTERN.matcher(processed).replaceAll("<em>$1</em>");

        // 处理链接
        processed = LINK_PATTERN.matcher(processed).replaceAll("<a href='$2'>$1</a>");

        // 处理引用来源
        processed = REFERENCE_SOURCE_PATTERN.matcher(processed).replaceAll(
                "<span class='reference-source'>【$1】</span>"
        );

        // 处理列表和表格
        String[] lines = processed.split("\n");
        StringBuilder finalHtml = new StringBuilder();
        boolean inList = false;
        boolean inNumberedList = false;

        for (int i = 0; i < lines.length; ) {
            String line = lines[i];

            // 表格块
            if (looksLikeTableRow(line) && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
                if (inList) { finalHtml.append("</ul>"); inList = false; }
                if (inNumberedList) { finalHtml.append("</ol>"); inNumberedList = false; }

                String headerLine = lines[i];
                String sepLine = lines[i + 1];
                java.util.List<String> body = new java.util.ArrayList<>();
                i += 2;
                while (i < lines.length && looksLikeTableRow(lines[i])) {
                    body.add(lines[i]);
                    i++;
                }
                appendTableHtml(finalHtml, headerLine, sepLine, body);
                continue;
            }

            // 无序列表
            if (LIST_PATTERN.matcher(line).matches()) {
                if (!inList) { finalHtml.append("<ul>"); inList = true; }
                line = LIST_PATTERN.matcher(line).replaceAll("<li>$1</li>");
                finalHtml.append(line);
                i++;
                continue;
            }

            // 有序列表
            if (NUMBERED_LIST_PATTERN.matcher(line).matches()) {
                if (!inNumberedList) { finalHtml.append("<ol>"); inNumberedList = true; }
                line = NUMBERED_LIST_PATTERN.matcher(line).replaceAll("<li>$1</li>");
                finalHtml.append(line);
                i++;
                continue;
            }

            // 普通段落/行
            if (inList) { finalHtml.append("</ul>"); inList = false; }
            if (inNumberedList) { finalHtml.append("</ol>"); inNumberedList = false; }

            if (!line.trim().isEmpty()) {
                finalHtml.append(line).append("<br>");
            }
            i++;
        }

        if (inList) finalHtml.append("</ul>");
        if (inNumberedList) finalHtml.append("</ol>");

        html.append("<div style='margin-bottom: 16px;'>").append(finalHtml).append("</div>");

        return html.toString();
    }

    /**
     * 转换部分 Markdown
     */
    private String convertPartialMarkdown(String content) {
        // 对于流式内容，我们进行最少的处理以避免破坏不完整的 Markdown
        String processed = escapeHtml(content);

        // 只处理完整的行内代码
        if (countOccurrences(processed, "`") % 2 == 0) {
            processed = INLINE_CODE_PATTERN.matcher(processed).replaceAll(
                    "<span class='inline-code'>$1</span>"
            );
        }

        // 将换行符转换为 <br>
        processed = processed.replace("\n", "<br>");

        return processed;
    }

    /**
     * 追加纯文本降级方案
     */
    private void appendPlainText(String text, MessageType type) {
        try {
            String label = "";
            if (type == MessageType.STUDENT) {
                label = "<div class='student-label'>Student:</div>";
            } else if (type == MessageType.ASSISTANT) {
                label = "<div class='assistant-label'>CodePilot:</div>";
            }

            String escaped = escapeHtml(text).replace("\n", "<br>");
            htmlContent.append(label).append("<div>").append(escaped).append("</div>");
            editorPane.setText(wrapInHtml(htmlContent.toString()));
        } catch (Exception e) {
            LOG.error("Failed to append plain text", e);
        }
    }

    /**
     * 将内容包装在 HTML 文档结构中
     */
    private String wrapInHtml(String content) {
        return String.format("<html><head><style>%s</style></head><body>%s</body></html>",
                inlineStyles, content);
    }

    /**
     * 转义 HTML 特殊字符
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * 计算字符串中子串的出现次数
     */
    private int countOccurrences(String str, String substr) {
        return str.length() - str.replace(substr, "").length();
    }

    /**
     * 规范化管道符
     */
    private static String normalizePipes(String s) {
        return s == null ? "" : s.replace('\uFF5C', '|');
    }

    /**
     * 判断是否看起来像表格行
     */
    private static boolean looksLikeTableRow(String line) {
        return TABLE_ROW_LINE.matcher(line).matches();
    }

    /**
     * 判断是否是表格分隔符
     */
    private static boolean isTableSeparator(String line) {
        return TABLE_SEP_LINE.matcher(line).matches();
    }

    /**
     * 分割表格行
     */
    private static String[] splitTableRow(String line) {
        String t = normalizePipes(line).trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        String[] cells = t.split("\\|", -1); // 保留空单元格
        for (int i = 0; i < cells.length; i++) cells[i] = cells[i].trim();
        return cells;
    }

    /**
     * 从分隔符单元格获取对齐方式
     */
    private static String alignFromSepCell(String sepCell) {
        String c = sepCell.trim();
        boolean left = c.startsWith(":");
        boolean right = c.endsWith(":");
        if (left && right) return "center";
        if (right) return "right";
        return "left"; // Markdown 默认左对齐
    }

    /**
     * 追加表格 HTML
     */
    private static void appendTableHtml(StringBuilder out, String headerLine, String sepLine, java.util.List<String> bodyLines) {
        String[] headers = splitTableRow(headerLine);
        String[] seps = splitTableRow(sepLine);
        int cols = Math.max(headers.length, seps.length);

        String[] aligns = new String[cols];
        for (int i = 0; i < cols; i++) {
            String sepCell = i < seps.length ? seps[i] : "---";
            aligns[i] = alignFromSepCell(sepCell);
        }

        out.append("<table><thead><tr>");
        for (int c = 0; c < cols; c++) {
            String h = c < headers.length ? headers[c] : "";
            out.append("<th style='text-align:").append(aligns[c]).append("'>").append(h).append("</th>");
        }
        out.append("</tr></thead><tbody>");

        for (String row : bodyLines) {
            String[] cells = splitTableRow(row);
            out.append("<tr>");
            for (int c = 0; c < cols; c++) {
                String v = c < cells.length ? cells[c] : "";
                out.append("<td style='text-align:").append(aligns[c]).append("'>").append(v).append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</tbody></table>");
    }

    /**
     * 消息类型枚举
     */
    public enum MessageType {
        STUDENT,
        ASSISTANT,
        SYSTEM,
        PLAIN
    }
}
