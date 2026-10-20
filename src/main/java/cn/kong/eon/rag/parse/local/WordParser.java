package cn.kong.eon.rag.parse.local;

import cn.kong.eon.rag.parse.Block;
import cn.kong.eon.rag.parse.DocumentParser;
import cn.kong.eon.rag.parse.TableMarkdown;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Word 解析：docx 走 POI 结构化（标题样式/表格），doc 老格式经 Tika 导出 XHTML 后转 Block。
 */
@Component
public class WordParser implements DocumentParser {

    private final TikaFallback tikaFallback;

    public WordParser(TikaFallback tikaFallback) {
        this.tikaFallback = tikaFallback;
    }

    @Override
    public List<String> extensions() {
        return List.of("doc", "docx");
    }

    @Override
    public List<Block> parse(byte[] bytes, String fileType) {
        if ("docx".equals(fileType)) {
            return parseDocx(bytes);
        }
        return tikaFallback.parseStructured(bytes);
    }

    private List<Block> parseDocx(byte[] bytes) {
        List<Block> blocks = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    String text = p.getText().strip();
                    if (text.isEmpty()) {
                        continue;
                    }
                    Integer level = headingLevel(p);
                    if (level != null) {
                        blocks.add(new Block(Block.BlockType.HEADING, level, text, 0, 0, Map.of()));
                    } else {
                        blocks.add(new Block(Block.BlockType.PARAGRAPH, 0, text, 0, 0, Map.of()));
                    }
                } else if (element instanceof XWPFTable table) {
                    emitTable(blocks, table);
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("docx parse failed: " + e.getMessage(), e);
        }
        return blocks;
    }

    /** Word 内置标题样式 Heading1~6（数字样式 id）→ 层级 */
    private Integer headingLevel(XWPFParagraph p) {
        String style = p.getStyleID();
        if (style == null) {
            return null;
        }
        String normalized = style.toLowerCase().replace("heading", "").replace("berschrift", "").trim();
        try {
            int level = Integer.parseInt(normalized);
            return level >= 1 && level <= 6 ? level : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void emitTable(List<Block> blocks, XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText());
            }
            rows.add(cells);
        }
        String markdown = TableMarkdown.render(rows);
        if (!markdown.isBlank()) {
            blocks.add(new Block(Block.BlockType.TABLE, 0, markdown, 0, 0,
                    Map.of("rows", String.valueOf(rows.size()))));
        }
    }
}
