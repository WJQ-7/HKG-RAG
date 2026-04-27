package com.bistu.hkgrag.preprocess.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.usermodel.*;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.OfficeXmlFileException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipException;

/**
 * 判断文档类型并提供多种文本提取方式(txt文本、pdf、doc、docx、xls、xlsx、ppt、pptx)
 *
 * @author wangjingqi
 */
public class TextExtractor {
    // 自动判断类型
    public static String extractText(String name, byte[] bytes) throws Exception {

        if (bytes == null || bytes.length == 0) {
            // 记录日志或返回一个特殊的标志字符串
            System.out.println("警告：文件 [" + name + "] 内容为空，跳过解析。");
            return ""; // 返回空字符串，让 DocumentLoader 继续
        }

        // 1. 纯文本优先处理（只能依赖文件名后缀）
        if (name.toLowerCase().endsWith(".txt")) {
            return new String(bytes);
        }

        // 使用 ByteArrayInputStream，它支持 mark 和 reset 操作
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            // 2. 使用 FileMagic 判断文件类型
            FileMagic fileMagic = FileMagic.valueOf(in);

            // 每次检查 FileMagic 后，流的指针会移动，所以需要重置流
            in.reset();
            switch (fileMagic) {
                // OOXML (ZIP/New Office Format: .docx, .xlsx, .pptx)
                case OOXML:
                    // 由于 FileMagic.OOXML 无法区分 DOCX, XLSX, PPTX，
                    String lowerName = name.toLowerCase();
                    if (lowerName.endsWith(".docx")) return extractDocx(in);
                    //if (lowerName.endsWith(".xlsx")) return extractXlsx(in);
                    //if (lowerName.endsWith(".pptx")) return extractPptx(in);
                    break;

                // OLE2 (Old Office Format: .doc, .xls, .ppt)
                case OLE2:
                    // OLE2 也无法区分 DOC, XLS, PPT。
                    // 我们可以尝试按 Word -> Excel -> PowerPoint 的顺序解析，
                    if (name.toLowerCase().endsWith(".doc")) {
                        return extractDoc(in);
                    }
                    // 对于 Excel 和 PowerPoint，我们优先使用 FileMagic OLE2 + 文件后缀
                    //if (name.toLowerCase().endsWith(".xls")) return extractXls(in);
                    //if (name.toLowerCase().endsWith(".ppt")) return extractPpt(in);
                    break;

                // PDF
//                case PDF:
//                    return extractPdf(in);

                // 其他（如纯文本、未知类型）
                case UNKNOWN:
                default:
                    // 尝试用 Docx 解析器处理，因为 FileMagic.OOXML 有时会误判。
                    // 鉴于上面已经用 OLE2 和 OOXML 尝试处理过，这里可以认定为不支持的格式。
                    System.out.println("未知或不支持的类型：" + name);
                    break;
            }
        } catch (NotOfficeXmlFileException | ZipException e) {
            // 捕获 XWPFDocument/XSSFWorkbook/XMLSlideShow 抛出的“文件结构损坏”的异常
            throw new IllegalArgumentException("文件无法识别或已损坏（OOXML格式）。", e);
        } catch (OfficeXmlFileException e) {
            // 捕获 HWPFDocument/HSSFWorkbook/HSLFSlideShow 抛出的“文件结构损坏”的异常
            throw new IllegalArgumentException("文件无法识别或已损坏（OLE2格式）。", e);
        } catch (Exception e) {
            throw new RuntimeException("处理文件时发生错误: " + e.getMessage(), e);
        }

        System.out.println("不支持的类型：" + name);
        return "";
    }

    // PDF
    private static String extractPdf(InputStream in) throws Exception {
        try (PDDocument doc = PDDocument.load(in)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }


    //    private static String extractDocx(InputStream in) throws Exception {
//        StringBuilder sb = new StringBuilder();
//
//        try (XWPFDocument doc = new XWPFDocument(in)) {
//
//            for (IBodyElement element : doc.getBodyElements()) {
//
//                // ===== 普通段落 =====
//                if (element instanceof XWPFParagraph) {
//                    XWPFParagraph p = (XWPFParagraph) element;
//                    String text = p.getText().trim();
//
//                    if (!text.isEmpty()) {
//                        sb.append(text).append("\n");
//                    }
//                }
//
//                // ===== 表格 =====
//                else if (element instanceof XWPFTable) {
//                    XWPFTable table = (XWPFTable) element;
//
//                    sb.append("\n"); // 表格前换行
//
//                    for (XWPFTableRow row : table.getRows()) {
//                        for (XWPFTableCell cell : row.getTableCells()) {
//
//                            String text = cell.getText()
//                                    .replaceAll("[\\r\\n]+", " ")
//                                    .trim();
//
//                            if (!text.isEmpty()) {
//                                sb.append(text).append("\t"); // 用 tab 分列
//                            }
//                        }
//                        sb.append("\n");
//                    }
//
//                    sb.append("\n"); // 表格后换行
//                }
//            }
//        }
//
//        return sb.toString();
//    }
//    private static String extractDocx(InputStream in) throws Exception {
//        StringBuilder sb = new StringBuilder();
//
//        try (XWPFDocument doc = new XWPFDocument(in)) {
//
//            for (IBodyElement element : doc.getBodyElements()) {
//
//                // ===== 只保留普通段落 =====
//                if (element instanceof XWPFParagraph) {
//                    XWPFParagraph p = (XWPFParagraph) element;
//                    String text = p.getText();
//
//                    if (text != null) {
//                        text = text.trim();
//                        if (!text.isEmpty()) {
//                            sb.append(text).append("\n");
//                        }
//                    }
//                }
//
//                // ===== 表格：直接跳过 =====
//                else if (element instanceof XWPFTable) {
//                    continue;
//                }
//            }
//        }
//
//        return sb.toString();
//    }
    private static String extractDocx(InputStream in) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(in)) {

            // ❗只要存在表格，直接丢弃整个文档
            if (!doc.getTables().isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();

            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();

                if (text != null) {
                    text = text.trim();

                    if (!text.isEmpty()) {
                        sb.append(text).append("\n");
                    }
                }
            }

            return sb.toString();
        }
    }

//    private static String extractDoc(InputStream in) throws Exception {
//        StringBuilder sb = new StringBuilder();
//
//        try (HWPFDocument doc = new HWPFDocument(in)) {
//            Range range = doc.getRange();
//
//            // ===== 1. 先记录所有表格位置 =====
//            TableIterator tableIterator = new TableIterator(range);
//            List<Table> tables = new ArrayList<>();
//
//            while (tableIterator.hasNext()) {
//                tables.add(tableIterator.next());
//            }
//
//            int tableIndex = 0;
//
//            // ===== 2. 遍历段落 =====
//            for (int i = 0; i < range.numParagraphs(); i++) {
//                Paragraph p = range.getParagraph(i);
//
//                // ===== 命中表格起点 =====
//                if (tableIndex < tables.size()) {
//                    Table table = tables.get(tableIndex);
//
//                    if (p.getStartOffset() == table.getStartOffset()) {
//
//                        sb.append("\n");
//
//                        for (int r = 0; r < table.numRows(); r++) {
//                            TableRow row = table.getRow(r);
//
//                            for (int c = 0; c < row.numCells(); c++) {
//                                TableCell cell = row.getCell(c);
//
//                                String text = cell.text()
//                                        .replace("\u0007", "")
//                                        .replaceAll("[\\r\\n]", " ")
//                                        .trim();
//
//                                if (!text.isEmpty()) {
//                                    sb.append(text).append("\t");
//                                }
//                            }
//                            sb.append("\n");
//                        }
//
//                        sb.append("\n");
//
//                        // 跳过整个表格
//                        int end = table.getEndOffset();
//                        while (i < range.numParagraphs()
//                                && range.getParagraph(i).getEndOffset() < end) {
//                            i++;
//                        }
//                        i--;
//
//                        tableIndex++;
//                        continue;
//                    }
//                }
//
//                // ===== 普通文本 =====
//                if (!p.isInTable()) {
//                    String text = p.text()
//                            .replaceAll("[\\r\\n\\u0007]", "")
//                            .trim();
//
//                    if (!text.isEmpty()) {
//                        sb.append(text).append("\n");
//                    }
//                }
//            }
//        }
//
//        return sb.toString();
//    }

    //    private static String extractDoc(InputStream in) throws Exception {
//        StringBuilder sb = new StringBuilder();
//
//        try (HWPFDocument doc = new HWPFDocument(in)) {
//            Range range = doc.getRange();
//
//            TableIterator tableIterator = new TableIterator(range);
//            List<Table> tables = new ArrayList<>();
//
//            while (tableIterator.hasNext()) {
//                tables.add(tableIterator.next());
//            }
//
//            int tableIndex = 0;
//
//            for (int i = 0; i < range.numParagraphs(); i++) {
//                Paragraph p = range.getParagraph(i);
//
//                // ===== 如果命中表格，直接跳过整个表格 =====
//                if (tableIndex < tables.size()) {
//                    Table table = tables.get(tableIndex);
//
//                    if (p.getStartOffset() == table.getStartOffset()) {
//
//                        int end = table.getEndOffset();
//
//                        // 跳过整个表格范围
//                        while (i < range.numParagraphs()
//                                && range.getParagraph(i).getEndOffset() < end) {
//                            i++;
//                        }
//                        i--;
//
//                        tableIndex++;
//                        continue;
//                    }
//                }
//
//                // ===== 只保留普通文本 =====
//                if (!p.isInTable()) {
//                    String text = p.text();
//
//                    if (text != null) {
//                        text = text.replaceAll("[\\r\\n\\u0007]", "").trim();
//
//                        if (!text.isEmpty()) {
//                            sb.append(text).append("\n");
//                        }
//                    }
//                }
//            }
//        }
//
//        return sb.toString();
//    }
    private static String extractDoc(InputStream in) throws Exception {
        try (HWPFDocument doc = new HWPFDocument(in)) {
            Range range = doc.getRange();

            TableIterator tableIterator = new TableIterator(range);

            // ❗只要存在表格，直接丢弃整个文档
            if (tableIterator.hasNext()) {
                return "";
            }

            // ===== 无表格才继续解析 =====
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < range.numParagraphs(); i++) {
                Paragraph p = range.getParagraph(i);

                if (!p.isInTable()) {
                    String text = p.text();

                    if (text != null) {
                        text = text.replaceAll("[\\r\\n\\u0007]", "").trim();

                        if (!text.isEmpty()) {
                            sb.append(text).append("\n");
                        }
                    }
                }
            }

            return sb.toString();
        }
    }

    // xlsx
    private static String extractXlsx(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Workbook wb = new XSSFWorkbook(in)) {
            readWorkbook(wb, sb);
            return sb.toString();
        }
    }


    // xls
    private static String extractXls(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Workbook wb = new HSSFWorkbook(in)) {
            readWorkbook(wb, sb);
            return sb.toString();
        } catch (org.apache.poi.hssf.OldExcelFormatException e) {
            // 捕获 BIFF5 格式异常
            System.err.println("警告：文件为旧版 Excel (BIFF5) 格式，POI 不支持，已跳过解析。");
            // 或者抛出一个更温和的业务异常
            // throw new UnsupportedFileFormatException("不支持的旧版 Excel BIFF5 格式", e);
            return "无法解析：旧版 Excel (BIFF5) 格式";
        }
    }

    private static void readWorkbook(Workbook wb, StringBuilder sb) {
        for (Sheet sheet : wb) {
            int maxCol = 10; // 或动态计算最大列数

            for (Row row : sheet) {
                for (int i = 0; i < maxCol; i++) {
                    Cell cell = row.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    sb.append(cell.toString()).append("\t");
                }
                sb.append("\n");
            }
            sb.append("----Sheet End----\n");
        }
    }

    // pptx
    private static String extractPptx(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(in)) {
            for (XSLFSlide slide : ppt.getSlides()) {
                sb.append(slide.getTitle()).append("\n");
                sb.append(slide.getNotes()).append("\n");
            }
        }
        return sb.toString();
    }

    // ppt
    private static String extractPpt(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (HSLFSlideShow ppt = new HSLFSlideShow(in)) {
            for (HSLFSlide slide : ppt.getSlides()) {
                sb.append(slide.getTitle()).append("\n");
            }
        }
        return sb.toString();
    }
}
