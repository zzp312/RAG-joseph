package com.xushu.rag.utils;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;
import technology.tabula.*;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Java原生文档解析器，替代Python脚本实现多格式文档解析
 * <p>
 * 支持格式：PDF（文本+表格）、DOCX（文本+表格+图片）、XLSX（表格结构化）
 * 输出格式：兼容原Python脚本的JSON结构 {text, chunks: [{text, page|sheet, type}]}
 * </p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class JavaDocumentParser {

    /**
     * 解析文档文件，按格式分派
     *
     * @param filePath 文件路径
     * @return 解析结果JSON
     * @throws IOException 文件读取或解析异常
     */
    public JSONObject parse(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("文件不存在: " + filePath);
        }

        String fileName = file.getName().toLowerCase();
        long startTime = System.currentTimeMillis();
        JSONObject result;

        if (fileName.endsWith(".pdf")) {
            result = parsePdf(file);
        } else if (fileName.endsWith(".docx")) {
            result = parseDocx(file);
        } else if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
            result = parseXlsx(file);
        } else {
            throw new IOException("JavaDocumentParser不支持的格式: " + fileName);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("JavaDocumentParser解析完成，文件: {}，耗时: {}ms", file.getName(), elapsed);

        return result;
    }

    /**
     * PDF文本提取器
     */
    private JSONObject parsePdf(File file) throws IOException {
        JSONObject result = new JSONObject();
        JSONArray chunks = new JSONArray();
        StringBuilder fullText = new StringBuilder();

        try (PDDocument document = PDDocument.load(file)) {
            int totalPages = document.getNumberOfPages();

            // 1. 提取表格（Tabula）
            List<TablePage> tablePages = extractTables(file);
            java.util.Map<Integer, List<String>> tableTextByPage = new java.util.HashMap<>();
            for (TablePage tp : tablePages) {
                int pageNum = tp.pageNumber;
                tableTextByPage.computeIfAbsent(pageNum, k -> new ArrayList<>());
                for (Table table : tp.tables) {
                    String md = tableToMarkdown(table);
                    if (md != null && !md.isEmpty()) {
                        tableTextByPage.get(pageNum).add(md);
                    }
                }
            }

            // 2. 逐页提取文本（PDFBox）
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String pageText = stripper.getText(document).trim();

                if (!pageText.isEmpty()) {
                    fullText.append(pageText).append("\n");

                    // 文本chunk
                    JSONObject textChunk = new JSONObject();
                    textChunk.put("text", pageText);
                    textChunk.put("page", pageNum);
                    textChunk.put("type", "text");
                    chunks.add(textChunk);
                }

                // 表格chunk
                List<String> tablesForPage = tableTextByPage.get(pageNum);
                if (tablesForPage != null) {
                    for (String tableMd : tablesForPage) {
                        JSONObject tableChunk = new JSONObject();
                        tableChunk.put("text", tableMd);
                        tableChunk.put("page", pageNum);
                        tableChunk.put("type", "table");
                        chunks.add(tableChunk);
                        fullText.append(tableMd).append("\n");
                    }
                }
            }

        } catch (IOException e) {
            log.error("PDFBox解析PDF失败: {}", file.getName(), e);
            throw e;
        }

        result.put("text", fullText.toString().trim());
        result.put("chunks", chunks);
        return result;
    }

    /**
     * 使用Tabula提取PDF中的表格
     */
    private List<TablePage> extractTables(File file) throws IOException {
        try {
            ObjectExtractor extractor = new ObjectExtractor(PDDocument.load(file));
            SpreadsheetExtractionAlgorithm sea = new SpreadsheetExtractionAlgorithm();
            PageIterator pageIterator = extractor.extract();

            List<TablePage> allTables = new ArrayList<>();
            int pageIndex = 1;
            while (pageIterator.hasNext()) {
                Page page = pageIterator.next();
                List<Table> tables = sea.extract(page);
                if (!tables.isEmpty()) {
                    TablePage tp = new TablePage();
                    tp.pageNumber = pageIndex;
                    tp.tables = tables;
                    allTables.add(tp);
                }
                pageIndex++;
            }
            extractor.close();
            log.debug("Tabula表格提取完成，共{}个页面包含表格", allTables.size());
            return allTables;

        } catch (Exception e) {
            log.warn("Tabula表格提取失败，将跳过表格解析: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 将Tabula表格转换为Markdown格式
     */
    private String tableToMarkdown(Table table) {
        try {
            List<List<RectangularTextContainer>> rows = table.getRows();
            if (rows.isEmpty()) {
                return null;
            }

            StringBuilder md = new StringBuilder();

            // 表头
            List<RectangularTextContainer> headerRow = rows.get(0);
            md.append("|");
            for (RectangularTextContainer cell : headerRow) {
                md.append(" ").append(cell.getText().replace("\n", " ").trim()).append(" |");
            }
            md.append("\n");

            // 分隔线
            md.append("|");
            for (int i = 0; i < headerRow.size(); i++) {
                md.append(" --- |");
            }
            md.append("\n");

            // 数据行
            for (int r = 1; r < rows.size(); r++) {
                md.append("|");
                List<RectangularTextContainer> row = rows.get(r);
                for (RectangularTextContainer cell : row) {
                    md.append(" ").append(cell.getText().replace("\n", " ").trim()).append(" |");
                }
                md.append("\n");
            }

            return md.toString().trim();

        } catch (Exception e) {
            log.warn("表格Markdown转换失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析DOCX文档，提取文本和表格
     *
     * @author Joseph
     */
    private JSONObject parseDocx(File file) throws IOException {
        JSONObject result = new JSONObject();
        JSONArray chunks = new JSONArray();
        StringBuilder fullText = new StringBuilder();
        int chunkId = 0;

        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {

            // 1. 提取段落文本
            StringBuilder docText = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.trim().isEmpty()) {
                    docText.append(text).append("\n");
                }
            }
            if (docText.length() > 0) {
                fullText.append(docText);
                JSONObject textChunk = new JSONObject();
                textChunk.put("text", docText.toString().trim());
                textChunk.put("page", 1);
                textChunk.put("type", "text");
                chunks.add(textChunk);
            }

            // 2. 提取表格（Markdown格式）
            List<XWPFTable> tables = document.getTables();
            for (int i = 0; i < tables.size(); i++) {
                XWPFTable table = tables.get(i);
                String md = docxTableToMarkdown(table);
                if (md != null && !md.isEmpty()) {
                    JSONObject tableChunk = new JSONObject();
                    tableChunk.put("text", md);
                    tableChunk.put("page", 1);
                    tableChunk.put("type", "table");
                    tableChunk.put("table_index", i);
                    chunks.add(tableChunk);
                    fullText.append(md).append("\n");
                }
            }
        }

        result.put("text", fullText.toString().trim());
        result.put("chunks", chunks);
        return result;
    }

    /**
     * 解析XLSX/XLS电子表格，按Sheet分行列结构化输出
     *
     * @author Joseph
     */
    private JSONObject parseXlsx(File file) throws IOException {
        JSONObject result = new JSONObject();
        JSONArray chunks = new JSONArray();
        StringBuilder fullText = new StringBuilder();

        try (FileInputStream fis = new FileInputStream(file);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            int sheetCount = workbook.getNumberOfSheets();
            for (int s = 0; s < sheetCount; s++) {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(s);
                String sheetName = sheet.getSheetName();

                int rowCount = sheet.getLastRowNum() + 1;
                if (rowCount <= 0) continue;

                // 每Sheet作为一个大表格chunk
                StringBuilder sheetMd = new StringBuilder();
                sheetMd.append("**Sheet: ").append(sheetName).append("**\n\n");

                // 表头（第一行）
                org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(0);
                if (headerRow != null) {
                    sheetMd.append("|");
                    int maxCol = headerRow.getLastCellNum();
                    for (int c = 0; c < maxCol; c++) {
                        org.apache.poi.ss.usermodel.Cell cell = headerRow.getCell(c);
                        String val = cell != null ? cell.toString().trim() : "";
                        sheetMd.append(" ").append(val.isEmpty() ? "-" : val).append(" |");
                    }
                    sheetMd.append("\n");
                    // 分隔线
                    sheetMd.append("|");
                    for (int c = 0; c < maxCol; c++) {
                        sheetMd.append(" --- |");
                    }
                    sheetMd.append("\n");

                    // 数据行（限制最多200行）
                    int maxRows = Math.min(rowCount, 200);
                    int rowIndex = 1;
                    for (int r = 1; r < maxRows; r++) {
                        org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                        if (row == null) continue;
                        sheetMd.append("|");
                        for (int c = 0; c < maxCol; c++) {
                            org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                            String val = cell != null ? cell.toString().trim().replace("\n", " ") : "";
                            sheetMd.append(" ").append(val.isEmpty() ? "-" : val).append(" |");
                        }
                        sheetMd.append("\n");
                        rowIndex++;
                    }
                    if (rowCount > 200) {
                        sheetMd.append("*（表格共").append(rowCount).append("行，此处仅展示前200行）*\n");
                    }
                }

                JSONObject sheetChunk = new JSONObject();
                sheetChunk.put("text", sheetMd.toString().trim());
                sheetChunk.put("page", s + 1);
                sheetChunk.put("type", "table");
                sheetChunk.put("sheet_name", sheetName);
                chunks.add(sheetChunk);
                fullText.append(sheetMd).append("\n");
            }
        }

        result.put("text", fullText.toString().trim());
        result.put("chunks", chunks);
        return result;
    }

    /**
     * DOCX表格转Markdown
     */
    private String docxTableToMarkdown(XWPFTable table) {
        try {
            List<XWPFTableRow> rows = table.getRows();
            if (rows.isEmpty()) return null;

            StringBuilder md = new StringBuilder();
            boolean hasHeader = false;

            for (int r = 0; r < rows.size(); r++) {
                XWPFTableRow row = rows.get(r);
                List<XWPFTableCell> cells = row.getTableCells();
                md.append("|");
                for (XWPFTableCell cell : cells) {
                    md.append(" ").append(cell.getText().replace("\n", " ").trim()).append(" |");
                }
                md.append("\n");
                // 第一行后加分隔线
                if (r == 0 && rows.size() > 1) {
                    md.append("|");
                    for (int i = 0; i < cells.size(); i++) {
                        md.append(" --- |");
                    }
                    md.append("\n");
                }
            }
            return md.toString().trim();
        } catch (Exception e) {
            log.warn("DOCX表格转换失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 表格页内部类
     */
    private static class TablePage {
        int pageNumber;
        List<Table> tables;
    }
}
