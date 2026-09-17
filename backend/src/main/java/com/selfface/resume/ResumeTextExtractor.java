package com.selfface.resume;

import com.selfface.common.BizException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 从简历文件里抽出纯文本。支持 PDF / DOCX / TXT。
 * 注意：大量简历用表格排版，Word 解析必须连表格一起读，否则经历部分会整段丢失。
 */
@Component
public class ResumeTextExtractor {

    private static final int MAX_CHARS = 40_000;

    public String extract(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException("文件内容为空");
        }
        String lower = fileName == null ? "" : fileName.toLowerCase();
        String text;
        if (lower.endsWith(".pdf")) {
            text = fromPdf(bytes);
        } else if (lower.endsWith(".docx")) {
            text = fromDocx(bytes);
        } else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            text = fromPlainText(bytes);
        } else if (lower.endsWith(".doc")) {
            throw new BizException("暂不支持旧版 .doc。请用 Word 另存为 .docx 或导出 PDF 后重试。");
        } else {
            throw new BizException("不支持的文件类型，请上传 PDF、DOCX 或 TXT");
        }
        text = text.replace("\u0000", "").replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
        if (text.isBlank()) {
            throw new BizException("没能从这份文件里读出文字。如果是扫描件或图片版简历，请先转成文字版 PDF 再上传。");
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS);
        }
        return text;
    }

    private String fromPdf(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (Exception e) {
            throw new BizException("PDF 解析失败：" + e.getMessage() + "。若文件已加密，请先去掉密码。");
        }
    }

    private String fromDocx(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                if (!p.getText().isBlank()) {
                    sb.append(p.getText()).append('\n');
                }
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder line = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String cellText = cell.getText().replace("\n", " ").trim();
                        if (!cellText.isEmpty()) {
                            line.append(cellText).append(" | ");
                        }
                    }
                    if (!line.isEmpty()) {
                        sb.append(line).append('\n');
                    }
                }
            }
        } catch (Exception e) {
            throw new BizException("Word 解析失败：" + e.getMessage());
        }
        return sb.toString();
    }

    private String fromPlainText(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        // UTF-8 解码出现替换字符，说明源文件很可能是 GBK
        if (utf8.indexOf('\uFFFD') >= 0) {
            utf8 = new String(bytes, Charset.forName("GBK"));
        }
        return utf8;
    }
}
