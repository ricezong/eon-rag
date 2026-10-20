package cn.kong.eon.rag.parse;

/**
 * 解析层结构化中间表示（Block IR）。
 *
 * 各格式解析器统一产出有序 Block 序列，下游分块层彻底脱离对原始文件格式的感知。
 * meta 约定键：file_name（文档名）/ page_number（PDF 页码）/
 * sheet_name（Excel 工作表名）/ slide_number（PPT 页序）/ title（块标题）/ rows（表格行数）。
 *
 * @param type      块类型
 * @param level     标题层级（仅 HEADING 有效，1 起算；其余为 0）
 * @param text      块文本（TABLE/SLIDE 为 Markdown 表示）
 * @param startPage 起始物理页码（无页概念的格式为 0）
 * @param endPage   结束物理页码
 * @param meta      结构化定位元数据
 */
public record Block(
        BlockType type,
        int level,
        String text,
        int startPage,
        int endPage,
        java.util.Map<String, String> meta
) {

    public enum BlockType {
        HEADING,
        PARAGRAPH,
        LIST,
        CODE,
        /** 表格（Markdown 表示），父块独占 + 表头重放 */
        TABLE,
        /** 单页幻灯片，父块独占 */
        SLIDE
    }

    /** 原子块：父块切分时不与普通正文混合 */
    public boolean isAtomic() {
        return type == BlockType.TABLE || type == BlockType.SLIDE;
    }
}
