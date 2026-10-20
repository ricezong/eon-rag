package cn.kong.eon.rag.parse.local;

import cn.kong.eon.rag.config.properties.KbParseProperties;
import cn.kong.eon.rag.parse.Block;
import cn.kong.eon.rag.parse.DocumentParser;
import cn.kong.eon.rag.parse.ScanDetector;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 解析：字号跳变 + 加粗属性 + 单行短文本启发式识别章节标题，
 * 页码写入 meta.page_number，页均字符数参与扫描件检测。
 */
@Component
public class PdfParser implements DocumentParser {

    /** 标题行最大长度（超过视为正文） */
    private static final int HEADING_MAX_LEN = 60;
    /** 相对正文字号的标题判定倍率 */
    private static final float HEADING_FONT_RATIO = 1.15f;

    private final KbParseProperties parseProperties;

    public PdfParser(KbParseProperties parseProperties) {
        this.parseProperties = parseProperties;
    }

    @Override
    public List<String> extensions() {
        return List.of("pdf");
    }

    @Override
    public List<Block> parse(byte[] bytes, String fileType) {
        List<Line> lines;
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            LayoutStripper stripper = new LayoutStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(doc.getNumberOfPages());
            stripper.getText(doc);
            lines = stripper.lines;
        } catch (IOException e) {
            throw new IllegalArgumentException("pdf parse failed: " + e.getMessage(), e);
        }

        float baseFont = baseFontSize(lines);
        List<Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        int paragraphPage = 0;
        Map<Integer, StringBuilder> pageTexts = new java.util.TreeMap<>();

        for (Line line : lines) {
            pageTexts.computeIfAbsent(line.page(), p -> new StringBuilder()).append(line.text());

            int headingLevel = headingLevelOf(line, baseFont);
            if (headingLevel > 0) {
                flushParagraph(blocks, paragraph, paragraphPage);
                blocks.add(new Block(Block.BlockType.HEADING, headingLevel, line.text(),
                        line.page(), line.page(),
                        Map.of("page_number", String.valueOf(line.page()))));
                continue;
            }
            if (paragraph.length() == 0) {
                paragraphPage = line.page();
            } else if (line.page() != paragraphPage) {
                // 跨页断段
                flushParagraph(blocks, paragraph, paragraphPage);
                paragraphPage = line.page();
            } else {
                paragraph.append('\n');
            }
            paragraph.append(line.text());
            if (endsSentence(line.text())) {
                flushParagraph(blocks, paragraph, paragraphPage);
            }
        }
        flushParagraph(blocks, paragraph, paragraphPage);

        ScanDetector.assertNotScanned(
                pageTexts.values().stream().map(StringBuilder::toString).toList(),
                parseProperties.getMinTextChars());
        return blocks;
    }

    /** 句末标点判定：行以句号/问号/叹号/分号结尾则段落闭合 */
    private static boolean endsSentence(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char c = text.charAt(text.length() - 1);
        return c == '。' || c == '！' || c == '？' || c == '；' || c == '.' || c == '!' || c == '?';
    }

    /** 标题判定：加粗或字号 ≥ 基准×1.15，且行长 ≤ 60；层级按字号分桶（1~3） */
    private int headingLevelOf(Line line, float baseFont) {
        if (line.text().length() > HEADING_MAX_LEN || baseFont <= 0) {
            return 0;
        }
        boolean bigOrBold = line.bold() || line.maxFont() >= baseFont * HEADING_FONT_RATIO;
        if (!bigOrBold) {
            return 0;
        }
        if (line.maxFont() >= baseFont * 1.5f) {
            return 1;
        }
        if (line.maxFont() >= baseFont * 1.3f) {
            return 2;
        }
        return 3;
    }

    /** 基准正文字号：出现频次最高的行字号（按 0.5 归桶） */
    private static float baseFontSize(List<Line> lines) {
        Map<Float, Integer> freq = new HashMap<>();
        for (Line line : lines) {
            if (!line.bold()) {
                freq.merge(Math.round(line.maxFont() * 2) / 2.0f, 1, Integer::sum);
            }
        }
        float best = 0;
        int bestCount = 0;
        for (Map.Entry<Float, Integer> e : freq.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static void flushParagraph(List<Block> blocks, StringBuilder paragraph, int page) {
        String text = paragraph.toString().strip();
        paragraph.setLength(0);
        if (!text.isEmpty()) {
            blocks.add(new Block(Block.BlockType.PARAGRAPH, 0, text, page, page,
                    Map.of("page_number", String.valueOf(page))));
        }
    }

    /** 布局行：页码、行最大字号、是否加粗、行文本 */
    private record Line(int page, float maxFont, boolean bold, String text) {
    }

    /** 按 Y 基线聚合同一行的多个文本片段，提取字号与字体属性 */
    private static final class LayoutStripper extends PDFTextStripper {

        final List<Line> lines = new ArrayList<>();
        private StringBuilder current = new StringBuilder();
        private float currentY = Float.NaN;
        private float currentMaxFont = 0;
        private boolean currentBold = false;

        LayoutStripper() throws IOException {
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null || text.isBlank() || positions.isEmpty()) {
                return;
            }
            float y = positions.get(0).getYDirAdj();
            float maxFont = 0;
            boolean bold = false;
            for (TextPosition tp : positions) {
                maxFont = Math.max(maxFont, tp.getFontSizeInPt());
                String font = tp.getFont() == null ? null : tp.getFont().getName();
                if (font != null && font.toLowerCase().contains("bold")) {
                    bold = true;
                }
            }
            if (!Float.isNaN(currentY) && Math.abs(y - currentY) > 1.5f) {
                flushLine();
            }
            if (current.length() == 0) {
                currentY = y;
                currentMaxFont = 0;
                currentBold = false;
            }
            current.append(text);
            currentMaxFont = Math.max(currentMaxFont, maxFont);
            currentBold = currentBold || bold;
        }

        private void flushLine() {
            String text = current.toString().strip();
            if (!text.isEmpty()) {
                lines.add(new Line(getCurrentPageNo(), currentMaxFont, currentBold, text));
            }
            current = new StringBuilder();
            currentY = Float.NaN;
        }

        @Override
        public String getText(PDDocument document) throws IOException {
            String result = super.getText(document);
            flushLine();
            return result;
        }
    }
}
