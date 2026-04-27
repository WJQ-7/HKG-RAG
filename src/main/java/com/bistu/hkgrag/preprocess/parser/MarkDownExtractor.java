package com.bistu.hkgrag.preprocess.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class MarkDownExtractor {
    public static String extractMarkdown(String name, byte[] bytes) throws Exception {

        if (bytes == null || bytes.length == 0) return "";

        String lower = name.toLowerCase();

        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {

            if (lower.endsWith(".docx")) {
                return docxToMarkdown(in);
            }

            if (lower.endsWith(".doc")) {
                // doc 转 markdown（弱支持）
                System.out.println("跳过 .doc 文件：" + name);
                return "";
            }

            if (lower.endsWith(".pdf")) {
                return pdfToMarkdown(in);
            }

            if (lower.endsWith(".txt")) {
                return new String(bytes);
            }

        }

        return "";
    }

    /**
     * DOCX → Markdown（重点）
     */
    private static String docxToMarkdown(InputStream in) throws Exception {
        StringBuilder md = new StringBuilder();

        try (XWPFDocument doc = new XWPFDocument(in)) {

            for (IBodyElement element : doc.getBodyElements()) {

                // ===== 段落 =====
                if (element instanceof XWPFParagraph) {
                    XWPFParagraph p = (XWPFParagraph) element;

                    String text = p.getText().trim();
                    if (text.isEmpty()) continue;

                    // 判断标题级别
                    String style = p.getStyle();

                    if (style != null && style.startsWith("Heading")) {
                        int level = 1;
                        try {
                            level = Integer.parseInt(style.replace("Heading", ""));
                        } catch (Exception ignored) {}

                        md.append("#".repeat(level)).append(" ").append(text).append("\n\n");
                    } else {
                        md.append(text).append("\n\n");
                    }
                }

                // ===== 表格 =====
                else if (element instanceof XWPFTable) {
                    XWPFTable table = (XWPFTable) element;

                    for (int i = 0; i < table.getRows().size(); i++) {
                        XWPFTableRow row = table.getRow(i);

                        md.append("| ");
                        for (XWPFTableCell cell : row.getTableCells()) {
                            md.append(cell.getText().replace("\n", " ").trim()).append(" | ");
                        }
                        md.append("\n");

                        // 表头分隔
                        if (i == 0) {
                            md.append("| ");
                            for (int j = 0; j < row.getTableCells().size(); j++) {
                                md.append("--- | ");
                            }
                            md.append("\n");
                        }
                    }

                    md.append("\n");
                }
            }
        }

        return md.toString();
    }

    /**
     * PDF → Markdown（简单版）
     */
    private static String pdfToMarkdown(InputStream in) throws Exception {

        try (PDDocument doc = PDDocument.load(in)) {

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);

            // 简单规则：按行转 Markdown
            StringBuilder md = new StringBuilder();
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // 简单标题判断（全大写 or 长度短）
                if (line.length() < 20) {
                    md.append("## ").append(line).append("\n\n");
                } else {
                    md.append(line).append("\n\n");
                }
            }

            return md.toString();
        }
    }
}
