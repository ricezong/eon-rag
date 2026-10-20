package cn.kong.eon.rag.parse.local;

import cn.kong.eon.rag.parse.Block;
import cn.kong.eon.rag.parse.DocumentParser;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * HTML 解析：jsoup 清除脚本与危险标签（XSS 防范）→
 * flexmark-html2md-converter 转 Markdown → 复用 MarkdownParser 统一解析。
 */
@Component
public class HtmlParser implements DocumentParser {

    private final MarkdownParser markdownParser;

    public HtmlParser(MarkdownParser markdownParser) {
        this.markdownParser = markdownParser;
    }

    @Override
    public List<String> extensions() {
        return List.of("html", "htm");
    }

    @Override
    public List<Block> parse(byte[] bytes, String fileType) {
        return parseHtml(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 供 XHTML 来源（Tika 导出）复用：html/xhtml 字符串 → Block 序列 */
    public List<Block> parseHtml(String html) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);
        // 清除脚本与危险标签，防止其文本内容混入知识库
        doc.select("script, style, noscript, iframe, object, embed").remove();
        // 保留标题层级映射
        com.vladsch.flexmark.util.data.MutableDataSet options =
                new com.vladsch.flexmark.util.data.MutableDataSet();
        options.set(FlexmarkHtmlConverter.SETEXT_HEADINGS, true);
        String markdown = FlexmarkHtmlConverter.builder(options).build()
                .convert(doc.body());
        return markdownParser.parse(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8), "md");
    }
}
