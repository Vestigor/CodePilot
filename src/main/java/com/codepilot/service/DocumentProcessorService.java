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

/**
 * Improved Document Processor with page-based chunking
 * Inspired by com.tongji.jea's approach
 */
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
     * Process all documents with intelligent caching
     */
    public List<KnowledgeEntry> processAllDocuments() {
        // Check if we have a cached knowledge base
        if (KnowledgeBaseLoader.cacheExists(PLUGIN_DATA_PATH)) {
            LOG.info("Loading knowledge base from cache");
            List<KnowledgeEntry> cachedEntries = KnowledgeBaseLoader.loadFromFile(PLUGIN_DATA_PATH);
            if (!cachedEntries.isEmpty()) {
                LOG.info("Successfully loaded " + cachedEntries.size() + " entries from cache");
                return cachedEntries;
            }
        }

        // If no cache or cache is empty, process documents
        LOG.info("Processing documents to build knowledge base");
        List<KnowledgeEntry> allEntries = new ArrayList<>();
        String materialsPath = configManager.getCourseMaterialsPath();

        try {
            // Get list of course materials
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

            // Process each file
            for (String fileName : fileNames) {
                String filePath = materialsPath + "/" + fileName;
                LOG.info("Processing: " + fileName);

                try {
                    InputStream is = getClass().getClassLoader().getResourceAsStream(filePath);
                    if (is == null) {
                        LOG.warn("File not found: " + filePath);
                        continue;
                    }

                    // Create temp file for processing
                    File tempFile = createTempFile(is, fileName);

                    // Process based on file type
                    List<KnowledgeEntry> entries = processDocument(tempFile, fileName);

                    if (!entries.isEmpty()) {
                        allEntries.addAll(entries);
                        LOG.info("Extracted " + entries.size() + " entries from " + fileName);
                    }

                    // Clean up temp file
                    tempFile.delete();

                } catch (Exception e) {
                    LOG.error("Failed to process " + fileName, e);
                }
            }

            // Save to cache for next time
            if (!allEntries.isEmpty()) {
                KnowledgeBaseLoader.saveToFile(allEntries, PLUGIN_DATA_PATH);
            }

        } catch (Exception e) {
            LOG.error("Failed to process documents", e);
        }

        LOG.info("Total knowledge entries created: " + allEntries.size());
        return allEntries;
    }

    /**
     * Process a single document into knowledge entries (one per page)
     */
    private List<KnowledgeEntry> processDocument(File file, String fileName) {
        String extension = getFileExtension(fileName);

        try {
            switch (extension.toLowerCase()) {
                case "pdf":
                    return processPDFByPage(file, fileName);
                case "docx":
                    return processDOCXByPage(file, fileName);
                case "txt":
                    return processTXTByPage(file, fileName);
                case "pptx":
                    return processPPTXBySlide(file, fileName);
                default:
                    LOG.warn("Unsupported file type: " + extension);
                    return new ArrayList<>();
            }
        } catch (Exception e) {
            LOG.error("Failed to process " + fileName, e);
            return new ArrayList<>();
        }
    }

    /**
     * Process PDF file - one entry per page
     */
    private List<KnowledgeEntry> processPDFByPage(File file, String fileName) throws IOException {
        List<KnowledgeEntry> entries = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                try {
                    // Extract text from single page
                    stripper.setStartPage(pageNum);
                    stripper.setEndPage(pageNum);
                    String pageText = stripper.getText(document);

                    if (pageText != null && !pageText.trim().isEmpty()) {
                        // Clean the text
                        pageText = cleanText(pageText);

                        // Create knowledge entry for this page
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
     * Process DOCX file - chunk by logical sections
     */
    private List<KnowledgeEntry> processDOCXByPage(File file, String fileName) throws IOException {
        List<KnowledgeEntry> entries = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {

            StringBuilder pageContent = new StringBuilder();
            int pageNumber = 1;
            int paragraphCount = 0;
            final int PARAGRAPHS_PER_PAGE = 20; // Approximate page size

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.trim().isEmpty()) {
                    pageContent.append(text).append("\n\n");
                    paragraphCount++;

                    // When we have enough content for a "page"
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

            // Add remaining content
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
     * Process PPTX file - one entry per slide
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

                // Extract text from all shapes in the slide
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
     * Process TXT file - chunk by size
     */
    private List<KnowledgeEntry> processTXTByPage(File file, String fileName) throws IOException {
        List<KnowledgeEntry> entries = new ArrayList<>();
        String content = Files.readString(file.toPath());

        // Split by lines and create chunks
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

        // Add remaining content
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
     * Clean text content
     */
    private String cleanText(String text) {
        if (text == null) return "";

        // Remove excessive whitespace
        text = text.replaceAll("\\s+", " ");

        // Remove control characters
        text = text.replaceAll("[\\p{Cntrl}&&[^\t\n\r]]", "");

        // Normalize line breaks
        text = text.replaceAll("\\r\\n|\\r", "\n");

        // Trim
        text = text.trim();

        return text;
    }

    /**
     * Process documents directly from resources
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

        // Save to cache
        if (!entries.isEmpty()) {
            KnowledgeBaseLoader.saveToFile(entries, PLUGIN_DATA_PATH);
        }

        return entries;
    }

    /**
     * Create temporary file from input stream
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
     * Get file extension
     */
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1);
        }
        return "";
    }

    /**
     * Clear all caches
     */
    public void clearCache() {
        documentCache.clear();
        KnowledgeBaseLoader.clearCache(PLUGIN_DATA_PATH);
    }

    /**
     * Force reprocess all documents
     */
    public List<KnowledgeEntry> reprocessAllDocuments() {
        clearCache();
        return processAllDocuments();
    }
}
