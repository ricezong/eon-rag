package cn.kong.eon.rag.parse.local;

import cn.kong.eon.rag.parse.Block;
import cn.kong.eon.rag.parse.DocumentParser;
import cn.kong.eon.rag.parse.TableMarkdown;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Markdown 解析：flexmark AST——
 * # 标题、围栏代码块、列表与管道表格直接映射为对应类型 Block。
 * 表格保留源文本（含表头与分隔行），供切分时重放表头。
 */
@Component
public class MarkdownParser implements DocumentParser {

    private final Parser parser;

    public MarkdownParser() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(com.vladsch.flexmark.ext.tables.TablesExtension.create()));
        this.parser = Parser.builder(options).build();
    }

    @Override
    public List<String> extensions() {
        return List.of("md", "markdown");
    }

    @Override
    public List<Block> parse(byte[] bytes, String fileType) {
        Node document = parser.parse(new String(bytes, StandardCharsets.UTF_8));
        List<Block> blocks = new ArrayList<>();
        for (Node child : document.getChildren()) {
            emitNode(blocks, child);
        }
        return blocks;
    }

    private void emitNode(List<Block> blocks, Node node) {
        if (node instanceof Heading heading) {
            String text = textOf(heading);
            if (!text.isEmpty()) {
                blocks.add(new Block(Block.BlockType.HEADING, heading.getLevel(), text, 0, 0, Map.of()));
            }
        } else if (node instanceof HtmlBlock htmlBlock) {
            emitHtmlTable(blocks, htmlBlock.getChars().toString());
        } else if (node instanceof TableBlock table) {
            // 表格原样保留（含表头与分隔行），rows 统计管道行数
            String text = sourceText(table);
            if (!text.isBlank()) {
                int rows = (int) text.lines().filter(l -> l.strip().startsWith("|")).count();
                blocks.add(new Block(Block.BlockType.TABLE, 0, text, 0, 0,
                        Map.of("rows", String.valueOf(rows))));
            }
        } else if (node instanceof FencedCodeBlock fenced) {
            String text = fenced.getContentChars().toString().strip();
            if (!text.isBlank()) {
                blocks.add(new Block(Block.BlockType.CODE, 0, text, 0, 0, Map.of()));
            }
        } else if (node instanceof IndentedCodeBlock indented) {
            String text = indented.getContentChars().toString().strip();
            if (!text.isBlank()) {
                blocks.add(new Block(Block.BlockType.CODE, 0, text, 0, 0, Map.of()));
            }
        } else if (node instanceof BulletList || node instanceof OrderedList) {
            // 列表后紧跟的缩进代码块会被解析为列表项子节点，逐项收集避免吞块
            StringBuilder sb = new StringBuilder();
            for (Node item : node.getChildren()) {
                if (!(item instanceof com.vladsch.flexmark.ast.ListItem)) {
                    emitNode(blocks, item);
                    continue;
                }
                for (Node sub : item.getChildren()) {
                    if (sub instanceof FencedCodeBlock || sub instanceof IndentedCodeBlock) {
                        emitNode(blocks, sub);
                    } else {
                        sb.append(sub.getChars().toString().strip()).append('\n');
                    }
                }
            }
            String text = sb.toString().strip();
            if (!text.isBlank()) {
                blocks.add(new Block(Block.BlockType.LIST, 0, text, 0, 0, Map.of()));
            }
        } else if (node instanceof Paragraph paragraph) {
            String text = sourceText(paragraph);
            if (!text.isBlank()) {
                blocks.add(new Block(Block.BlockType.PARAGRAPH, 0, text, 0, 0, Map.of()));
            }
        } else {
            // 其余节点（引用块等）提取文本为段落
            for (Node child : node.getChildren()) {
                emitNode(blocks, child);
            }
        }
    }

    /** 标题纯文本：剥离强调等内联标记 */
    private static String textOf(Node node) {
        return new TextCollectingVisitor().collectAndGetText(node).strip();
    }

    /** 块级 HTML 中的表格转 Markdown 表格 */
    private void emitHtmlTable(List<Block> blocks, String html) {
        if (!html.contains("<table")) {
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (Element tr : Jsoup.parse(html).select("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : tr.select("th, td")) {
                cells.add(cell.text());
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        String markdown = TableMarkdown.render(rows);
        if (!markdown.isBlank()) {
            blocks.add(new Block(Block.BlockType.TABLE, 0, markdown, 0, 0,
                    Map.of("rows", String.valueOf(rows.size()))));
        }
    }

    /** 节点源文本（含子树），保留 Markdown 原始表示 */
    private static String sourceText(Node node) {
        BasedSequence chars = node.getChars();
        return (chars == null ? "" : chars.toString()).strip();
    }
}
