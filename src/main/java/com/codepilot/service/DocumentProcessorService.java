package com.codepilot.service;

import com.codepilot.model.KnowledgeEntry;
import com.codepilot.util.ConfigManager;
import com.codepilot.util.KnowledgeBaseLoader;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
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
    private final Map<String, List<KnowledgeEntry>> documentCache = new ConcurrentHashMap<>();
    private static final String PLUGIN_DATA_PATH = PathManager.getPluginsPath() + "/CodePilot/data";

    public DocumentProcessorService(Project project) {
        this.project = project;
        this.configManager = ApplicationManager.getApplication().getService(ConfigManager.class);
    }

    public static DocumentProcessorService getInstance(Project project) {
        return project.getService(DocumentProcessorService.class);
    }

    /**
     * 处理所有文档，并使用智能缓存
     */
    public List<KnowledgeEntry> processAllDocuments() {
        // 检查是否有缓存的知识库
        if (KnowledgeBaseLoader.cacheExists(PLUGIN_DATA_PATH)) {
            LOG.info("Loading knowledge base from cache");
            List<KnowledgeEntry> cachedEntries = KnowledgeBaseLoader.loadFromFile(PLUGIN_DATA_PATH);
            if (!cachedEntries.isEmpty()) {
                LOG.info("Successfully loaded " + cachedEntries.size() + " entries from cache");
                return cachedEntries;
            }
        }

        // 如果没有缓存或缓存为空，则处理文档
        LOG.info("Processing documents to build knowledge base");
        List<KnowledgeEntry> allEntries = new ArrayList<>();
        String materialsPath = configManager.getCourseMaterialsPath();

        try {
            // 获取课程资料列表
            InputStream listStream = getClass().getClassLoader()
                    .getResourceAsStream("course_materials.txt");

            if (listStream == null) {
                LOG.warn("course_materials.txt not found, trying direct approach");
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

            LOG.info("Found " + fileNames.size() + " files to process");

            // 处理每个文件
            for (String fileName : fileNames) {
                String filePath = materialsPath + "/" + fileName;
                LOG.info("Processing: " + fileName);

                try {
                    InputStream is = getClass().getClassLoader().getResourceAsStream(filePath);
                    if (is == null) {
                        LOG.warn("File not found: " + filePath);
                        continue;
                    }

                    // 创建临时文件进行处理
                    File tempFile = createTempFile(is, fileName);

                    // 根据文件类型处理
                    List<KnowledgeEntry> entries = processDocument(tempFile, fileName);

                    if (!entries.isEmpty()) {
                        allEntries.addAll(entries);
                        LOG.info("Extracted " + entries.size() + " entries from " + fileName);
                    }

                    // 清理临时文件
                    tempFile.delete();

                } catch (Exception e) {
                    LOG.error("Failed to process " + fileName, e);
                }
            }

        } catch (Exception e) {
            LOG.error("Failed to process documents", e);
        }

        LOG.info("Total knowledge entries created: " + allEntries.size());
        return allEntries;
    }

    /**
     * 处理单个文档并生成知识条目（每页一个条目）
     */
    private List<KnowledgeEntry> processDocument(File file, String fileName) {
        String extension = getFileExtension(fileName);

        try {
            return switch (extension.toLowerCase()) {
                case "pdf" -> processPDFByPage(file, fileName);
                case "docx" -> processDOCXByPage(file, fileName);
                case "txt" -> processTXTByPage(file, fileName);
                case "pptx" -> processPPTXBySlide(file, fileName);
                default -> {
                    LOG.warn("Unsupported file type: " + extension);
                    yield new ArrayList<>();
                }
            };
        } catch (Exception e) {
            LOG.error("Failed to process " + fileName, e);
            return new ArrayList<>();
        }
    }

    /**
     * 处理PDF文件 - 每页一个条目
     */
    private List<KnowledgeEntry> processPDFByPage(File file, String fileName) throws IOException {
        List<KnowledgeEntry> entries = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                try {
                    // 提取单页文本
                    stripper.setStartPage(pageNum);
                    stripper.setEndPage(pageNum);
                    String pageText = stripper.getText(document);

                    if (pageText != null && !pageText.trim().isEmpty()) {
                        // 清理文本
                        pageText = cleanText(pageText);

                        // 为此页面创建知识条目
                        String chunkId = KnowledgeEntry.generateChunkId(fileName, pageNum, 1);
                        KnowledgeEntry entry = new KnowledgeEntry(
                                chunkId,
                                pageText,
                                fileName,
                                pageNum,
                                "pdf"
                        );
                        entries.add(entry);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to extract page " + pageNum + " from " + fileName, e);
                }
            }
        }

        LOG.info("Extracted " + entries.size() + " pages from PDF: " + fileName);
        return entries;
    }

    /**
     * 处理DOCX文件 - 按逻辑部分分块
     */
    private List<KnowledgeEntry> processDOCXByPage(File file, String fileName) throws IOException {
        List<KnowledgeEntry> entries = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {

            StringBuilder pageContent = new StringBuilder();
            int pageNumber = 1;
            int paragraphCount = 0;
            final int PARAGRAPHS_PER_PAGE = 20; // 每页的大致段落数

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.trim().isEmpty()) {
                    pageContent.append(text).append("\n\n");
                    paragraphCount++;

                    // 如果段落数足够一页
                    if (paragraphCount >= PARAGRAPHS_PER_PAGE) {
                        String content = cleanText(pageContent.toString());
                        if (!content.isEmpty()) {
                            String chunkId = KnowledgeEntry.generateChunkId(fileName, pageNumber, 1);
                            KnowledgeEntry entry = new KnowledgeEntry(
                                    chunkId,
                                    content,
                                    fileName,
                                    pageNumber,
                                    "docx"
                            );
                            entries.add(entry);
                        }

                        pageContent.setLength(0);
                        paragraphCount = 0;
                        pageNumber++;
                    }
                }
            }

            // 添加剩余内容
            if (pageContent.length() > 0) {
                String content = cleanText(pageContent.toString());
                if (!content.isEmpty()) {
                    String chunkId = KnowledgeEntry.generateChunkId(fileName, pageNumber, 1);
                    KnowledgeEntry entry = new KnowledgeEntry(
                            chunkId,
                            content,
                            fileName,
                            pageNumber,
                            "docx"
                    );
                    entries.add(entry);
                }
            }
        }

        LOG.info("Extracted " + entries.size() + " sections from DOCX: " + fileName);
        return entries;
    }

    /**
     * 处理PPTX文件 - 每个幻灯片一个条目
     */
    private List<KnowledgeEntry> processPPTXBySlide(File file, String fileName) throws IOException {
        List<KnowledgeEntry> entries = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             XMLSlideShow ppt = new XMLSlideShow(fis)) {

            List<XSLFSlide> slides = ppt.getSlides();

            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                StringBuilder slideContent = new StringBuilder();
                int slideNumber = i + 1;

                // 提取幻灯片中所有形状的文本
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape) {
                        XSLFTextShape textShape = (XSLFTextShape) shape;
                        String text = textShape.getText();
                        if (text != null && !text.trim().isEmpty()) {
                            slideContent.append(text).append("\n");
                        }
                    }
                }

                String content = cleanText(slideContent.toString());
                if (!content.isEmpty()) {
                    String chunkId = KnowledgeEntry.generateChunkId(fileName, slideNumber, 1);
                    KnowledgeEntry entry = new KnowledgeEntry(
                            chunkId,
                            content,
                            fileName,
                            slideNumber,
                            "pptx"
                    );
                    entries.add(entry);
                }
            }
        }

        LOG.info("Extracted " + entries.size() + " slides from PPTX: " + fileName);
        return entries;
    }

    /**
     * 处理TXT文件 - 按行数分块
     */
    private List<KnowledgeEntry> processTXTByPage(File file, String fileName) throws IOException {
        List<KnowledgeEntry> entries = new ArrayList<>();
        String content = Files.readString(file.toPath());

        // 按行分割并创建块
        String[] lines = content.split("\n");
        final int LINES_PER_PAGE = 50;

        StringBuilder pageContent = new StringBuilder();
        int pageNumber = 1;
        int lineCount = 0;

        for (String line : lines) {
            pageContent.append(line).append("\n");
            lineCount++;

            if (lineCount >= LINES_PER_PAGE) {
                String pageText = cleanText(pageContent.toString());
                if (!pageText.isEmpty()) {
                    String chunkId = KnowledgeEntry.generateChunkId(fileName, pageNumber, 1);
                    KnowledgeEntry entry = new KnowledgeEntry(
                            chunkId,
                            pageText,
                            fileName,
                            pageNumber,
                            "txt"
                    );
                    entries.add(entry);
                }

                pageContent.setLength(0);
                lineCount = 0;
                pageNumber++;
            }
        }

        // 添加剩余内容
        if (pageContent.length() > 0) {
            String pageText = cleanText(pageContent.toString());
            if (!pageText.isEmpty()) {
                String chunkId = KnowledgeEntry.generateChunkId(fileName, pageNumber, 1);
                KnowledgeEntry entry = new KnowledgeEntry(
                        chunkId,
                        pageText,
                        fileName,
                        pageNumber,
                        "txt"
                );
                entries.add(entry);
            }
        }

        LOG.info("Extracted " + entries.size() + " pages from TXT: " + fileName);
        return entries;
    }

    /**
     * 清理文本内容
     */
    private String cleanText(String text) {
        if (text == null) return "";

        // 移除多余的空白字符
        text = text.replaceAll("\\s+", " ");

        // 移除控制字符
        text = text.replaceAll("[\\p{Cntrl}&&[^\t\n\r]]", "");

        // 标准化换行符
        text = text.replaceAll("\\r\\n|\\r", "\n");

        // 修剪
        text = text.trim();

        return text;
    }

    /**
     * 直接从资源处理文档
     */
    private List<KnowledgeEntry> processDocumentsDirectly(String materialsPath) {
        List<KnowledgeEntry> entries = new ArrayList<>();

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
                    List<KnowledgeEntry> fileEntries = processDocument(tempFile, fileName);
                    entries.addAll(fileEntries);
                    tempFile.delete();
                    LOG.info("Processed " + fileName + ": " + fileEntries.size() + " entries");
                }
            } catch (Exception e) {
                LOG.error("Failed to process " + fileName, e);
            }
        }

        return entries;
    }

    /**
     * 从输入流创建临时文件
     */
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

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1);
        }
        return "";
    }

    /**
     * 清除所有缓存
     */
    public void clearCache() {
        documentCache.clear();
        KnowledgeBaseLoader.clearCache(PLUGIN_DATA_PATH);
    }

}