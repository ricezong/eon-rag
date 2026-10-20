package cn.kong.eon.rag.parse.local;

import cn.kong.eon.rag.parse.Block;
import cn.kong.eon.rag.parse.DocumentParser;
import cn.kong.eon.rag.parse.TableMarkdown;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PPT 解析：pptx 单页幻灯片独占一个 SLIDE 块（页码入 meta，标题取标题占位符，
 * 文本框与表格均转文本）。ppt 老格式经 Tika 导出 XHTML 后转 Block。
 */
@Component
public class PptParser implements DocumentParser {

    private final TikaFallback tikaFallback;

    public PptParser(TikaFallback tikaFallback) {
        this.tikaFallback = tikaFallback;
    }

    @Override
    public List<String> extensions() {
        return List.of("ppt", "pptx");
    }

    @Override
    public List<Block> parse(byte[] bytes, String fileType) {
        if ("pptx".equals(fileType)) {
            return parsePptx(bytes);
        }
        return tikaFallback.parseStructured(bytes);
    }

    private List<Block> parsePptx(byte[] bytes) {
        List<Block> blocks = new ArrayList<>();
        try (XMLSlideShow slides = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            int pageNo = 0;
            for (XSLFSlide slide : slides.getSlides()) {
                pageNo++;
                String title = slideTitle(slide);
                StringBuilder body = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        for (XSLFTextParagraph paragraph : textShape.getTextParagraphs()) {
                            String line = runsText(paragraph);
                            if (!line.isBlank()) {
                                body.append(line.strip()).append('\n');
                            }
                        }
                    } else if (shape instanceof XSLFTable table) {
                        String markdown = TableMarkdown.render(tableRows(table));
                        if (!markdown.isBlank()) {
                            body.append(markdown).append('\n');
                        }
                    }
                }
                String text = body.toString().strip();
                if (text.isEmpty() && (title == null || title.isBlank())) {
                    continue;
                }
                Map<String, String> meta = title == null || title.isBlank()
                        ? Map.of("slide_number", String.valueOf(pageNo))
                        : Map.of("slide_number", String.valueOf(pageNo), "title", title);
                blocks.add(new Block(Block.BlockType.SLIDE, 0, text, pageNo, pageNo, meta));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("pptx parse failed: " + e.getMessage(), e);
        }
        return blocks;
    }

    /** 标题占位符文本；无占位符时返回 null */
    private String slideTitle(XSLFSlide slide) {
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape textShape
                    && textShape.getTextType() == Placeholder.TITLE) {
                String text = textShape.getText();
                return text == null || text.isBlank() ? null : text.strip();
            }
        }
        return null;
    }

    /** 幻灯片表格按行读取单元格文本 */
    private List<List<String>> tableRows(XSLFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XSLFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XSLFTableCell cell : row.getCells()) {
                cells.add(cell.getText());
            }
            rows.add(cells);
        }
        return rows;
    }

    private String runsText(XSLFTextParagraph paragraph) {
        StringBuilder sb = new StringBuilder();
        for (XSLFTextRun run : paragraph.getTextRuns()) {
            sb.append(run.getRawText());
        }
        return sb.toString();
    }
}
