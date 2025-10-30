package com.codepilot.util;

import com.intellij.openapi.diagnostic.Logger;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 代码清理工具类，用于处理LLM生成的代码
 */
public class CodeCleanupUtil {
    private static final Logger LOG = Logger.getInstance(CodeCleanupUtil.class);

    // 匹配代码块标记的正则表达式
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```\\w*\\n?([\\s\\S]*?)```",
            Pattern.MULTILINE
    );

    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");

    /**
     * 清理生成的代码，移除markdown标记和不必要的文本
     */
    public static String cleanGeneratedCode(String rawCode) {
        if (rawCode == null || rawCode.trim().isEmpty()) {
            return "";
        }

        String cleaned = rawCode;

        // 1. 提取代码块内容
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            cleaned = matcher.group(1);
        }

        // 2. 移除行内代码标记
        cleaned = cleaned.replaceAll("`", "");

        // 3. 移除常见的说明性文本
        cleaned = removeExplanatoryText(cleaned);

        // 4. 处理特定场景的代码
        cleaned = handleSpecificPatterns(cleaned);

        // 5. 修正缩进
        cleaned = fixIndentation(cleaned);

        return cleaned.trim();
    }

    /**
     * 清理测试代码，确保生成的是完整的测试类
     */
    public static String cleanTestCode(String rawCode, String className) {
        String cleaned = cleanGeneratedCode(rawCode);

        // 确保是完整的测试类
        if (!cleaned.contains("class " + className + "Test")) {
            // 如果只是测试方法，包装成完整的类
            if (cleaned.contains("@Test")) {
                cleaned = wrapInTestClass(cleaned, className);
            }
        }

        // 确保有必要的导入
        if (!cleaned.contains("import org.junit")) {
            cleaned = addTestImports(cleaned);
        }

        return cleaned;
    }

    /**
     * 清理提交消息
     */
    public static String cleanCommitMessage(String rawMessage) {
        if (rawMessage == null) {
            return "";
        }

        String cleaned = rawMessage.trim();

        // 移除markdown代码块标记
        cleaned = cleaned.replaceAll("```[\\s\\S]*?```", "");

        // 移除可能的引导文本
        String[] lines = cleaned.split("\n");
        StringBuilder result = new StringBuilder();

        for (String line : lines) {
            line = line.trim();
            // 跳过常见的说明性前缀
            if (!line.toLowerCase().startsWith("commit message:") &&
                    !line.toLowerCase().startsWith("生成的提交消息:") &&
                    !line.toLowerCase().startsWith("提交消息:") &&
                    !line.isEmpty()) {
                if (result.length() > 0) {
                    result.append("\n");
                }
                result.append(line);
            }
        }

        return result.toString().trim();
    }

    /**
     * 验证生成的代码是否适合插入到指定位置
     */
    public static boolean isValidForInsertion(String code, InsertionContext context) {
        if (code == null || code.trim().isEmpty()) {
            return false;
        }

        // 检查是否包含完整的类定义（不应该在类内部插入）
        if (context.isInsideClass() && containsClassDefinition(code)) {
            LOG.warn("Generated code contains class definition, not suitable for insertion inside class");
            return false;
        }

        // 检查是否包含包声明（不应该在类内部插入）
        if (context.isInsideClass() && code.contains("package ")) {
            LOG.warn("Generated code contains package declaration");
            return false;
        }

        return true;
    }

    /**
     * 根据上下文调整生成的代码
     */
    public static String adjustForContext(String code, InsertionContext context) {
        if (context.isInsideClass()) {
            // 如果在类内部，移除类定义，只保留方法
            code = extractMethodsOnly(code);
        }

        if (context.isInsideMethod()) {
            // 如果在方法内部，只保留语句
            code = extractStatementsOnly(code);
        }

        return code;
    }

    private static String removeExplanatoryText(String code) {
        String[] lines = code.split("\n");
        StringBuilder result = new StringBuilder();
        boolean inCode = false;

        for (String line : lines) {
            // 检测Java代码的开始
            if (line.contains("package ") || line.contains("import ") ||
                    line.contains("public class") || line.contains("public interface") ||
                    line.contains("@") || inCode) {
                inCode = true;
                result.append(line).append("\n");
            } else if (line.trim().startsWith("//") || line.trim().startsWith("/*") ||
                    line.trim().startsWith("*")) {
                // 保留注释
                result.append(line).append("\n");
            }
            // 跳过其他说明性文本
        }

        return result.toString();
    }

    private static String handleSpecificPatterns(String code) {
        // 处理可能的重复类定义
        if (code.contains("public class") || code.contains("class ")) {
            // 确保只有一个主类定义
            code = ensureSingleClassDefinition(code);
        }

        return code;
    }

    private static String ensureSingleClassDefinition(String code) {
        // 简单实现：如果检测到多个class定义，只保留第一个
        String[] parts = code.split("(?=public class|class )");
        if (parts.length > 2) {
            // 可能有多个类定义
            return parts[0] + parts[1]; // 保留第一个类
        }
        return code;
    }

    private static String fixIndentation(String code) {
        String[] lines = code.split("\n");
        StringBuilder result = new StringBuilder();
        int indentLevel = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            // 减少缩进级别
            if (trimmed.startsWith("}")) {
                indentLevel = Math.max(0, indentLevel - 1);
            }

            // 添加适当的缩进
            if (!trimmed.isEmpty()) {
                result.append("    ".repeat(indentLevel)).append(trimmed).append("\n");
            } else {
                result.append("\n");
            }

            // 增加缩进级别
            if (trimmed.endsWith("{") && !trimmed.startsWith("}")) {
                indentLevel++;
            }
        }

        return result.toString();
    }

    private static String wrapInTestClass(String testMethods, String className) {
        StringBuilder sb = new StringBuilder();
        sb.append("import org.junit.jupiter.api.Test;\n");
        sb.append("import static org.junit.jupiter.api.Assertions.*;\n\n");
        sb.append("public class ").append(className).append("Test {\n\n");
        sb.append(testMethods);
        sb.append("\n}");
        return sb.toString();
    }

    private static String addTestImports(String code) {
        StringBuilder imports = new StringBuilder();
        imports.append("import org.junit.jupiter.api.Test;\n");
        imports.append("import org.junit.jupiter.api.BeforeEach;\n");
        imports.append("import org.junit.jupiter.api.AfterEach;\n");
        imports.append("import static org.junit.jupiter.api.Assertions.*;\n\n");
        return imports.toString() + code;
    }

    private static boolean containsClassDefinition(String code) {
        return code.matches("(?s).*\\b(public\\s+)?class\\s+\\w+.*");
    }

    private static String extractMethodsOnly(String code) {
        // 提取方法定义，去除类声明
        String[] lines = code.split("\n");
        StringBuilder result = new StringBuilder();
        boolean inMethod = false;
        int braceCount = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            // 跳过包和类声明
            if (trimmed.startsWith("package ") ||
                    trimmed.matches(".*\\bclass\\s+\\w+.*")) {
                continue;
            }

            // 检测方法开始
            if (trimmed.matches(".*\\b(public|private|protected|static).*\\(.*\\).*\\{?.*")) {
                inMethod = true;
            }

            if (inMethod) {
                result.append(line).append("\n");

                // 计算大括号
                for (char c : trimmed.toCharArray()) {
                    if (c == '{') braceCount++;
                    if (c == '}') braceCount--;
                }

                // 方法结束
                if (braceCount == 0 && trimmed.contains("}")) {
                    inMethod = false;
                    result.append("\n");
                }
            }
        }

        return result.toString();
    }

    private static String extractStatementsOnly(String code) {
        // 提取纯语句，去除方法定义
        String[] lines = code.split("\n");
        StringBuilder result = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();

            // 跳过方法签名
            if (trimmed.matches(".*\\b(public|private|protected).*\\(.*\\).*") ||
                    trimmed.equals("{") || trimmed.equals("}")) {
                continue;
            }

            if (!trimmed.isEmpty()) {
                result.append(line).append("\n");
            }
        }

        return result.toString();
    }

    /**
     * 插入上下文
     */
    public static class InsertionContext {
        private final boolean insideClass;
        private final boolean insideMethod;
        private final String surroundingCode;

        public InsertionContext(boolean insideClass, boolean insideMethod, String surroundingCode) {
            this.insideClass = insideClass;
            this.insideMethod = insideMethod;
            this.surroundingCode = surroundingCode;
        }

        public boolean isInsideClass() { return insideClass; }
        public boolean isInsideMethod() { return insideMethod; }
        public String getSurroundingCode() { return surroundingCode; }
    }
}