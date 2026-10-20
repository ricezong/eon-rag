package cn.kong.eon.rag.parse;

import java.util.List;

/**
 * 二维表格转 Markdown 表示。各解析器共用。
 */
public final class TableMarkdown {

    private TableMarkdown() {
    }

    /** 单元格值内管道符转义，防止破坏列结构 */
    public static String escape(String cell) {
        return cell == null ? "" : cell.replace("|", "\\|").replace("\n", " ").trim();
    }

    /**
     * 行列表转 Markdown 表格。
     * 首行视为表头；不足两行的表格输出为管道拼接文本（无分隔行无法成表）。
     */
    public static String render(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        if (rows.size() == 1) {
            return String.join(" | ", rows.get(0).stream().map(TableMarkdown::escape).toList());
        }
        StringBuilder sb = new StringBuilder();
        appendRow(sb, rows.get(0));
        int cols = rows.get(0).size();
        sb.append('|');
        for (int i = 0; i < cols; i++) {
            sb.append(" --- |");
        }
        sb.append('\n');
        for (int i = 1; i < rows.size(); i++) {
            appendRow(sb, rows.get(i));
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> row) {
        sb.append('|');
        for (String cell : row) {
            sb.append(' ').append(escape(cell)).append(" |");
        }
        sb.append('\n');
    }
}
