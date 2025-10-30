package com.codepilot.service;

import com.codepilot.model.DocumentChunk;
import com.codepilot.util.ConfigManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xslf.usermodel.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@Service(Service.Level.PROJECT)
public final class DocumentProcessorService {
    private static final Logger LOG = Logger.getInstance(DocumentProcessorService.class);
    private final Project project;
    private final ConfigManager configManager;

    public DocumentProcessorService(Project project) {
        this.project = project;
        this.configManager = ApplicationManager.getApplication().getService(ConfigManager.class);
    }

    public static DocumentProcessorService getInstance(Project project) {
        return project.getService(DocumentProcessorService.class);
    }

    public List<DocumentChunk> processAllDocuments() {
        List<DocumentChunk> allChunks = new ArrayList<>();

        try {
            String materialsPath = configManager.getCourseMaterialsPath();
            InputStream is = getClass().getClassLoader().getResourceAsStream(materialsPath);

            if (is == null) {
                LOG.warn("Course materials directory not found: " + materialsPath);
                return allChunks;
            }

            // 获取资源目录下的所有文件
            File resourceDir = new File(getClass().getClassLoader()
                    .getResource(materialsPath).toURI());

            if (resourceDir.exists() && resourceDir.isDirectory()) {
                File[] files = resourceDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile()) {
                            List<DocumentChunk> chunks = processDocument(file);
                            allChunks.addAll(chunks);
                            LOG.info("Processed " + file.getName() + ": " + chunks.size() + " chunks");
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to process documents", e);
        }

        LOG.info("Total chunks processed: " + allChunks.size());
        return allChunks;
    }

    private List<DocumentChunk> processDocument(File file) {
        String fileName = file.getName();
        String extension = getFileExtension(fileName);

        try {
            switch (extension.toLowerCase()) {
                case "pdf":
                    return processPDF(file);
                case "docx":
                    return processDOCX(file);
                case "txt":
                    return processTXT(file);
                case "pptx":
                    return processPPTX(file);
                default:
                    LOG.warn("Unsupported file type: " + extension);
                    return new ArrayList<>();
            }
        } catch (Exception e) {
            LOG.error("Failed to process " + fileName, e);
            return new ArrayList<>();
        }
    }

    private List<DocumentChunk> processPDF(File file) throws IOException {
        List<DocumentChunk> chunks = new ArrayList<>();

        try (PDDocument document = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();

            for (int page = 1; page <= totalPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);

                List<String> pageChunks = splitIntoChunks(pageText);
                for (String chunkText : pageChunks) {
                    chunks.add(new DocumentChunk(chunkText, file.getName(), page));
                }
            }
        }

        return chunks;
    }

    private List<DocumentChunk> processDOCX(File file) throws IOException {
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
                            chunks.add(new DocumentChunk(chunkText, file.getName(), pageNumber));
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
                    chunks.add(new DocumentChunk(chunkText, file.getName(), pageNumber));
                }
            }
        }

        return chunks;
    }

    private List<DocumentChunk> processTXT(File file) throws IOException {
        List<DocumentChunk> chunks = new ArrayList<>();
        String content = new String(Files.readAllBytes(file.toPath()));

        List<String> textChunks = splitIntoChunks(content);
        int chunkIndex = 1;
        for (String chunkText : textChunks) {
            chunks.add(new DocumentChunk(chunkText, file.getName(), chunkIndex++));
        }

        return chunks;
    }

    private List<DocumentChunk> processPPTX(File file) throws IOException {
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
                        chunks.add(new DocumentChunk(chunkText, file.getName(), slideNumber));
                    }
                }

                slideNumber++;
            }
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
                int lastNewline = cleanText.lastIndexOf('\n', end);

                int boundary = Math.max(Math.max(lastPeriod, lastExclamation),
                        Math.max(lastQuestion, lastNewline));

                if (boundary > start) {
                    end = boundary + 1;
                }
            }

            String chunk = cleanText.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            start = end - overlap;
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
}
