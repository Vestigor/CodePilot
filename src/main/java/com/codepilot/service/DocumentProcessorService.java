package com.codepilot.service;

import com.codepilot.model.DocumentChunk;
import com.codepilot.util.ConfigManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xslf.usermodel.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service(Service.Level.PROJECT)
public final class DocumentProcessorService {
    private static final Logger LOG = Logger.getInstance(DocumentProcessorService.class);
    private final Project project;
    private final ConfigManager configManager;
    private final Map<String, List<DocumentChunk>> documentCache = new ConcurrentHashMap<>();

    public DocumentProcessorService(Project project) {
        this.project = project;
        this.configManager = ApplicationManager.getApplication().getService(ConfigManager.class);
    }

    public static DocumentProcessorService getInstance(Project project) {
        return project.getService(DocumentProcessorService.class);
    }

    public List<DocumentChunk> processAllDocuments() {
        List<DocumentChunk> allChunks = new ArrayList<>();
        String materialsPath = configManager.getCourseMaterialsPath();

        System.out.println("=== 开始加载课程材料 ===");

        try {
            // 读取文件列表
            InputStream listStream = getClass().getClassLoader()
                    .getResourceAsStream("course_materials.txt");

            if (listStream == null) {
                System.err.println("❌ 未找到课程材料列表文件");
                // 尝试直接读取course_materials目录
                return processDocumentsDirectly(materialsPath);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(listStream));
            List<String> fileNames = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    fileNames.add(line);
                }
            }
            reader.close();

            System.out.println("📁 找到 " + fileNames.size() + " 个文件\n");

            // 处理每个文件
            for (String fileName : fileNames) {
                String filePath = materialsPath + "/" + fileName;
                System.out.println("\n处理文件: " + filePath);

                try {
                    // 先检查缓存
                    if (documentCache.containsKey(fileName)) {
                        List<DocumentChunk> cachedChunks = documentCache.get(fileName);
                        allChunks.addAll(cachedChunks);
                        System.out.println("✓ " + fileName + " - 从缓存加载 " + cachedChunks.size() + " 个文档块");
                        continue;
                    }

                    // 使用 getResourceAsStream 读取单个文件
                    InputStream is = getClass().getClassLoader().getResourceAsStream(filePath);

                    if (is == null) {
                        System.err.println("✗ 文件不存在: " + filePath);
                        continue;
                    }

                    // 创建临时文件来处理
                    String extension = getFileExtension(fileName);
                    File tempFile = File.createTempFile("course_", "." + extension);
                    tempFile.deleteOnExit();

                    // 将 InputStream 写入临时文件
                    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                    is.close();

                    // 处理临时文件
                    List<DocumentChunk> chunks = processDocument(tempFile, fileName);

                    if (!chunks.isEmpty()) {
                        documentCache.put(fileName, chunks);
                        allChunks.addAll(chunks);
                        System.out.println("✓ " + fileName + " - 生成 " + chunks.size() + " 个文档块");
                    } else {
                        System.out.println("⚠ " + fileName + " - 未能提取内容");
                    }

                } catch (Exception e) {
                    System.err.println("✗ 处理文件失败: " + filePath);
                    LOG.error("Error processing file: " + filePath, e);
                }
            }

        } catch (Exception e) {
            System.err.println("❌ 加载课程材料失败: " + e.getMessage());
            LOG.error("Failed to load course materials", e);
        }

        System.out.println("\n=== 课程材料加载完成 ===");
        System.out.println("总计加载 " + allChunks.size() + " 个文档块");

        return allChunks;
    }

    private List<DocumentChunk> processDocumentsDirectly(String materialsPath) {
        List<DocumentChunk> chunks = new ArrayList<>();

        // 资源目录中的文件
        String[] knownFiles = {
                "Lec-00-Introduction.pdf",
                "Lec-01-Introduction-to-Java.pdf",
                "Lec-02-Variables-Operators-ControlFlowStatements-and-Arrays.pdf",
                "Lec-03-Numbers-and-Strings.pdf",
                "Lec-04-Classes-and-Objects.pdf",
                "Lec-05-Inheritance-and-Interfaces.pdf",
                "Lec-06-Exceptions.pdf",
                "Lec-07-Generics.pdf",
                "Lec-08-Annotations-and-Reflection.pdf"
        };

        for (String fileName : knownFiles) {
            String filePath = materialsPath + "/" + fileName;
            try {
                InputStream is = getClass().getClassLoader().getResourceAsStream(filePath);
                if (is != null) {
                    File tempFile = createTempFile(is, fileName);
                    List<DocumentChunk> fileChunks = processDocument(tempFile, fileName);
                    chunks.addAll(fileChunks);
                    System.out.println("✓ 处理文件: " + fileName + " - " + fileChunks.size() + " 个块");
                }
            } catch (Exception e) {
                LOG.error("Failed to process file: " + fileName, e);
            }
        }

        return chunks;
    }

    private File createTempFile(InputStream is, String fileName) throws IOException {
        String extension = getFileExtension(fileName);
        File tempFile = File.createTempFile("course_", "." + extension);
        tempFile.deleteOnExit();

        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
        is.close();

        return tempFile;
    }

    private List<DocumentChunk> processDocument(File file, String originalFileName) {
        String extension = getFileExtension(originalFileName);

        try {
            switch (extension.toLowerCase()) {
                case "pdf":
                    return processPDF(file, originalFileName);
                case "docx":
                    return processDOCX(file, originalFileName);
                case "txt":
                    return processTXT(file, originalFileName);
                case "pptx":
                    return processPPTX(file, originalFileName);
                default:
                    LOG.warn("Unsupported file type: " + extension);
                    return new ArrayList<>();
            }
        } catch (Exception e) {
            LOG.error("Failed to process " + originalFileName, e);
            // 尝试作为文本文件处理
            try {
                return processTXT(file, originalFileName);
            } catch (Exception fallbackError) {
                LOG.error("Fallback processing also failed for " + originalFileName, fallbackError);
                return new ArrayList<>();
            }
        }
    }

    private List<DocumentChunk> processPDF(File file, String originalFileName) throws IOException {
        List<DocumentChunk> chunks = new ArrayList<>();

        try {
            // 使用PDFBox 3.0的新加载方式
            PDDocument document = Loader.loadPDF(file);

            try {
                PDFTextStripper stripper = new PDFTextStripper();
                int totalPages = document.getNumberOfPages();

                // 如果页数太多，分批处理
                int batchSize = 10;
                for (int startPage = 1; startPage <= totalPages; startPage += batchSize) {
                    int endPage = Math.min(startPage + batchSize - 1, totalPages);

                    stripper.setStartPage(startPage);
                    stripper.setEndPage(endPage);

                    try {
                        String batchText = stripper.getText(document);

                        // 按页分割内容
                        String[] pageTexts = batchText.split("\\f"); // Form feed character often separates pages

                        for (int i = 0; i < pageTexts.length; i++) {
                            String pageText = pageTexts[i].trim();
                            if (!pageText.isEmpty()) {
                                List<String> pageChunks = splitIntoChunks(pageText);
                                for (String chunkText : pageChunks) {
                                    chunks.add(new DocumentChunk(chunkText, originalFileName, startPage + i));
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOG.warn("Failed to extract text from pages " + startPage + "-" + endPage + " in " + originalFileName, e);
                    }
                }
            } finally {
                document.close();
            }
        } catch (Exception e) {
            LOG.error("PDF processing failed for " + originalFileName, e);
            // 创建一个占位符块
            chunks.add(new DocumentChunk(
                    "PDF文件 " + originalFileName + " 的内容无法提取，可能包含图片或特殊格式。",
                    originalFileName,
                    1
            ));
        }

        return chunks;
    }

    private List<DocumentChunk> processDOCX(File file, String originalFileName) throws IOException {
        List<DocumentChunk> chunks = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {

            StringBuilder pageContent = new StringBuilder();
            int pageNumber = 1;
            int paragraphCount = 0;

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.trim().isEmpty()) {
                    pageContent.append(text).append("\n");
                    paragraphCount++;

                    // 每20段作为一页
                    if (paragraphCount >= 20) {
                        List<String> pageChunks = splitIntoChunks(pageContent.toString());
                        for (String chunkText : pageChunks) {
                            chunks.add(new DocumentChunk(chunkText, originalFileName, pageNumber));
                        }

                        pageContent.setLength(0);
                        paragraphCount = 0;
                        pageNumber++;
                    }
                }
            }

            // 处理剩余内容
            if (pageContent.length() > 0) {
                List<String> pageChunks = splitIntoChunks(pageContent.toString());
                for (String chunkText : pageChunks) {
                    chunks.add(new DocumentChunk(chunkText, originalFileName, pageNumber));
                }
            }
        } catch (Exception e) {
            LOG.error("DOCX processing failed for " + originalFileName, e);
            chunks.add(new DocumentChunk(
                    "Word文档 " + originalFileName + " 的内容无法提取。",
                    originalFileName,
                    1
            ));
        }

        return chunks;
    }

    private List<DocumentChunk> processTXT(File file, String originalFileName) throws IOException {
        List<DocumentChunk> chunks = new ArrayList<>();

        try {
            String content = new String(Files.readAllBytes(file.toPath()));
            List<String> textChunks = splitIntoChunks(content);
            int chunkIndex = 1;
            for (String chunkText : textChunks) {
                chunks.add(new DocumentChunk(chunkText, originalFileName, chunkIndex++));
            }
        } catch (Exception e) {
            LOG.error("TXT processing failed for " + originalFileName, e);
            chunks.add(new DocumentChunk(
                    "文本文件 " + originalFileName + " 的内容无法提取。",
                    originalFileName,
                    1
            ));
        }

        return chunks;
    }

    private List<DocumentChunk> processPPTX(File file, String originalFileName) throws IOException {
        List<DocumentChunk> chunks = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             XMLSlideShow ppt = new XMLSlideShow(fis)) {

            List<XSLFSlide> slides = ppt.getSlides();
            int slideNumber = 1;

            for (XSLFSlide slide : slides) {
                StringBuilder slideContent = new StringBuilder();

                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape) {
                        XSLFTextShape textShape = (XSLFTextShape) shape;
                        String text = textShape.getText();
                        if (text != null && !text.trim().isEmpty()) {
                            slideContent.append(text).append("\n");
                        }
                    }
                }

                String content = slideContent.toString();
                if (!content.trim().isEmpty()) {
                    List<String> slideChunks = splitIntoChunks(content);
                    for (String chunkText : slideChunks) {
                        chunks.add(new DocumentChunk(chunkText, originalFileName, slideNumber));
                    }
                }

                slideNumber++;
            }
        } catch (Exception e) {
            LOG.error("PPTX processing failed for " + originalFileName, e);
            chunks.add(new DocumentChunk(
                    "PPT文件 " + originalFileName + " 的内容无法提取。",
                    originalFileName,
                    1
            ));
        }

        return chunks;
    }

    private List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        int chunkSize = configManager.getChunkSize();
        int overlap = configManager.getChunkOverlap();

        String cleanText = text.replaceAll("\\s+", " ").trim();

        if (cleanText.length() <= chunkSize) {
            if (!cleanText.isEmpty()) {
                chunks.add(cleanText);
            }
            return chunks;
        }

        int start = 0;
        while (start < cleanText.length()) {
            int end = Math.min(start + chunkSize, cleanText.length());

            // 尝试在句子边界处分割
            if (end < cleanText.length()) {
                int lastPeriod = cleanText.lastIndexOf('。', end);
                int lastExclamation = cleanText.lastIndexOf('！', end);
                int lastQuestion = cleanText.lastIndexOf('？', end);
                int lastDot = cleanText.lastIndexOf('.', end);
                int lastNewline = cleanText.lastIndexOf('\n', end);

                int boundary = Math.max(Math.max(Math.max(lastPeriod, lastExclamation),
                        Math.max(lastQuestion, lastDot)), lastNewline);

                if (boundary > start && boundary < end) {
                    end = boundary + 1;
                }
            }

            String chunk = cleanText.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            start = Math.max(start + 1, end - overlap);
        }

        return chunks;
    }

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1);
        }
        return "";
    }

    public void clearCache() {
        documentCache.clear();
    }
}
