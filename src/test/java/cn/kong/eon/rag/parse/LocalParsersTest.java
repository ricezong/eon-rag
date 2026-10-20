package cn.kong.eon.rag.parse;

import cn.kong.eon.rag.common.exception.BizException;
import cn.kong.eon.rag.parse.local.ExcelParser;
import cn.kong.eon.rag.parse.local.HtmlParser;
import cn.kong.eon.rag.parse.local.MarkdownParser;
import cn.kong.eon.rag.parse.local.TxtParser;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯 JVM 解析器测试：Markdown / TXT / HTML / 扫描件检测 / 路由。
 */
class LocalParsersTest {

    private final MarkdownParser markdownParser = new MarkdownParser();
    private final TxtParser txtParser = new TxtParser();
    private final HtmlParser htmlParser = new HtmlParser(markdownParser);
    private final ExcelParser excelParser = new ExcelParser();

    @Test
    void excelParserTrimsGhostColumns() throws IOException {
        // 模拟带大量仅设样式、无值空列的表格；不裁剪时单行会膨胀到数万字符
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("记录");
            var style = workbook.createCellStyle();
            for (int r = 0; r < 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue(r == 0 ? "姓名" : "张三" + r);
                row.createCell(1).setCellValue(r == 0 ? "岗位" : "Java开发");
                row.createCell(2).setCellValue(r == 0 ? "结果" : "已发offer");
                for (int c = 100; c < 900; c++) {
                    row.createCell(c).setCellStyle(style);
                }
            }
            workbook.write(out);
        }
        List<Block> blocks = excelParser.parse(out.toByteArray(), "xlsx");
        assertEquals(1, blocks.size());
        Block table = blocks.get(0);
        assertEquals(Block.BlockType.TABLE, table.type());
        // 裁剪后仅剩 3 个有效列
        assertTrue(table.text().length() < 300, "幽灵列未裁剪，表格长度=" + table.text().length());
    }

    @Test
    void excelParserRendersCjkDateFormat() throws IOException {
        // numFmtId 58 为 CJK 内置日期格式，POI 不识别，需输出 ISO 日期而非序列号
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("记录");
            var style = workbook.createCellStyle();
            style.setDataFormat((short) 58);
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("日期");
            header.createCell(1).setCellValue("公司");
            Row row = sheet.createRow(1);
            Cell date = row.createCell(0);
            date.setCellValue(45901);
            date.setCellStyle(style);
            row.createCell(1).setCellValue("易芽");
            workbook.write(out);
        }
        String text = excelParser.parse(out.toByteArray(), "xlsx").get(0).text();
        assertTrue(text.contains("2025-09-01"), "日期未格式化，表格=" + text);
        assertFalse(text.contains("45901"), "日期仍为序列号，表格=" + text);
    }

    @Test
    void markdownParserHeadingTableCodeList() {
        String md = """
                # 一级标题

                第一段正文，包含多行
                软换行内容。

                | 列A | 列B |
                | --- | --- |
                | a1 | b1 |
                | a2 | b2 |

                ```java
                int x = 1;
                ```

                - 项目一
                - 项目二

                ## 二级标题

                结尾段落。
                """;
        List<Block> blocks = markdownParser.parse(md.getBytes(StandardCharsets.UTF_8), "md");

        assertEquals(7, blocks.size());
        assertEquals(Block.BlockType.HEADING, blocks.get(0).type());
        assertEquals(1, blocks.get(0).level());
        assertEquals("一级标题", blocks.get(0).text());

        assertEquals(Block.BlockType.PARAGRAPH, blocks.get(1).type());
        assertTrue(blocks.get(1).text().contains("软换行"));

        assertEquals(Block.BlockType.TABLE, blocks.get(2).type());
        assertTrue(blocks.get(2).text().contains("---"));
        assertEquals("4", blocks.get(2).meta().get("rows"));

        assertEquals(Block.BlockType.CODE, blocks.get(3).type());
        assertEquals("int x = 1;", blocks.get(3).text());

        assertEquals(Block.BlockType.LIST, blocks.get(4).type());
        assertEquals(Block.BlockType.HEADING, blocks.get(5).type());
        assertEquals(2, blocks.get(5).level());
    }

    @Test
    void txtParserUtf8AndGbk() {
        List<Block> utf8 = txtParser.parse("第一段。\n\n第二段。".getBytes(StandardCharsets.UTF_8), "txt");
        assertEquals(2, utf8.size());
        assertEquals("第一段。", utf8.get(0).text());

        byte[] gbk = "中文GBK编码内容".getBytes(java.nio.charset.Charset.forName("GBK"));
        List<Block> blocks = txtParser.parse(gbk, "txt");
        assertEquals("中文GBK编码内容", blocks.get(0).text());
    }

    @Test
    void htmlParserMapsHeadingsAndTables() {
        String html = """
                <html><body>
                <h1>标题</h1>
                <p>段落一</p>
                <table><tr><td>a</td><td>b</td></tr><tr><td>1</td><td>2</td></tr></table>
                <ul><li>项A</li><li>项B</li></ul>
                <pre><code>x = 1</code></pre>
                </body></html>
                """;
        List<Block> blocks = htmlParser.parse(html.getBytes(StandardCharsets.UTF_8), "html");
        // 4 块：html2md 将 pre 输出为 4 空格缩进代码，紧跟 ul 时按 CommonMark 语义
        // 成为列表项延续段落，内容并入 LIST 块
        assertEquals(4, blocks.size());
        assertEquals(Block.BlockType.HEADING, blocks.get(0).type());
        assertEquals("标题", blocks.get(0).text());
        assertEquals(Block.BlockType.TABLE, blocks.get(2).type());
        assertTrue(blocks.get(2).text().contains("| a | b |"));
        assertEquals(Block.BlockType.LIST, blocks.get(3).type());
        assertTrue(blocks.get(3).text().contains("项A"));
        assertTrue(blocks.get(3).text().contains("x = 1"));
    }

    @Test
    void scanDetectorBlocksEmptyText() {
        // 整篇有效字符（去空白）低于 min-text-chars
        assertThrows(BizException.class,
                () -> ScanDetector.assertNotScanned(List.of("短文本"), 50));
        // PDF 页均低于 10：3 页共 24 有效字符
        assertThrows(BizException.class,
                () -> ScanDetector.assertNotScanned(List.of("一二三四五六七八", "一二三四五六七八", "一二三四五六七八"), 50));
        // 空白不计入有效字符
        assertThrows(BizException.class,
                () -> ScanDetector.assertNotScanned(List.of("     "), 50));
        // 正常文本不阻断
        ScanDetector.assertNotScanned(
                List.of("正常段落内容。".repeat(50), "第二页正常内容。".repeat(50)), 50);
    }

    @Test
    void parseRouterDispatchesByExtension() {
        cn.kong.eon.rag.config.properties.KbParseProperties props =
                new cn.kong.eon.rag.config.properties.KbParseProperties();
        ParseRouter router = new ParseRouter(
                List.of(markdownParser, txtParser, htmlParser), markdownParser, props,
                java.util.Optional.empty());
        String longText = "正常段落内容，长度超过整篇扫描件阈值。".repeat(10);
        List<Block> blocks = router.parse(longText.getBytes(StandardCharsets.UTF_8), "md", null, null);
        assertEquals(1, blocks.size());
        assertThrows(BizException.class,
                () -> router.parse(new byte[0], "exe", null, null));
    }

    @Test
    void tableMarkdownEscapesPipes() {
        String md = TableMarkdown.render(List.of(
                List.of("h1", "h2"),
                List.of("a|b", "c\nd")));
        assertTrue(md.contains("a\\|b"));
        assertTrue(md.contains("c d"));
        assertTrue(md.contains("---"));
    }
}
