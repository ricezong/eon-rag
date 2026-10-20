package cn.kong.eon.rag.parse;

import java.util.List;

/**
 * 解析器 SPI：将原始文档转为有序 Block 序列。
 */
public interface DocumentParser {

    /** 支持的小写扩展名（不含点） */
    List<String> extensions();

    /**
     * 解析文档。
     *
     * @param bytes    文件字节
     * @param fileType 小写扩展名
     * @return 有序 Block 序列
     */
    List<Block> parse(byte[] bytes, String fileType);
}
