package cn.kong.eon.rag.parse.local;

import cn.kong.eon.rag.parse.Block;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.ToXMLContentHandler;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * Tika XHTML 模式降级解析（doc/ppt 旧二进制格式）：
 * 导出结构化 XHTML 后复用 HtmlParser 转 Block，保留标题与表格。
 */
@Component
public class TikaFallback {

    private final HtmlParser htmlParser;

    public TikaFallback(HtmlParser htmlParser) {
        this.htmlParser = htmlParser;
    }

    public List<Block> parseStructured(byte[] bytes) {
        try {
            AutoDetectParser parser = new AutoDetectParser();
            ToXMLContentHandler handler = new ToXMLContentHandler();
            parser.parse(new ByteArrayInputStream(bytes), handler, new Metadata());
            return htmlParser.parseHtml(handler.toString());
        } catch (Exception e) {
            throw new IllegalArgumentException("tika xhtml parse failed: " + e.getMessage(), e);
        }
    }
}
