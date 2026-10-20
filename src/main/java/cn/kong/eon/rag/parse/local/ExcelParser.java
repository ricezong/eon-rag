package cn.kong.eon.rag.parse.local;

import cn.kong.eon.rag.parse.Block;
import cn.kong.eon.rag.parse.DocumentParser;
import cn.kong.eon.rag.parse.TableMarkdown;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Excel 解析：每个 Sheet 输出一个 TABLE 块（Markdown 全量行），
 * sheet 名与行数入 meta，由父块切分器按行窗口拆分并重放表头。
 */
@Component
public class ExcelParser implements DocumentParser {

    @Override
    public List<String> extensions() {
        return List.of("xls", "xlsx");
    }

    @Override
    public List<Block> parse(byte[] bytes, String fileType) {
        List<Block> blocks = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                List<List<String>> rows = new ArrayList<>();
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        cells.add(cellText(cell, formatter));
                    }
                    rows.add(cells);
                }
                rows = trimToContent(rows);
                if (rows.isEmpty()) {
                    continue;
                }
                String markdown = TableMarkdown.render(rows);
                if (!markdown.isBlank()) {
                    blocks.add(new Block(Block.BlockType.TABLE, 0, markdown, 0, 0,
                            Map.of("sheet_name", sheet.getSheetName(),
                                    "rows", String.valueOf(rows.size()))));
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("excel parse failed: " + e.getMessage(), e);
        }
        return blocks;
    }

    /** 日期单元格输出 ISO 日期：内置 CJK 日期格式会被 POI 渲染成序列号 */
    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell.getCellType() == CellType.NUMERIC && isDateCell(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        return formatter.formatCellValue(cell);
    }

    /** POI 仅识别内置日期格式 14~22、45~47；CJK 区间（27~36、50~58）需自行判定 */
    private static boolean isDateCell(Cell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            return true;
        }
        int format = cell.getCellStyle().getDataFormat();
        return (format >= 27 && format <= 36) || (format >= 50 && format <= 58);
    }

    /**
     * 裁掉无内容的尾部列并丢弃全空行。
     * 表格常含被格式化过但无值的幽灵列，POI 会逐列遍历到该范围，
     * 直接渲染会让单行膨胀到数万字符，超出向量化接口单条文本上限。
     */
    private static List<List<String>> trimToContent(List<List<String>> rows) {
        int width = 0;
        for (List<String> row : rows) {
            for (int i = row.size() - 1; i >= 0; i--) {
                if (row.get(i) != null && !row.get(i).isBlank()) {
                    width = Math.max(width, i + 1);
                    break;
                }
            }
        }
        if (width == 0) {
            return List.of();
        }
        List<List<String>> trimmed = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> cells = new ArrayList<>(width);
            for (int i = 0; i < width; i++) {
                String value = i < row.size() ? row.get(i) : "";
                cells.add(value == null ? "" : value);
            }
            if (cells.stream().anyMatch(cell -> !cell.isBlank())) {
                trimmed.add(cells);
            }
        }
        return trimmed;
    }
}
