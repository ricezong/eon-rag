package cn.kong.eon.rag.chunk;

import cn.kong.eon.rag.config.properties.KbChunkProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 子块切分器：在每个父块内部独立运行，子块边界绝不跨越父块。
 *
 * 1. 非 TEXT 父块（TABLE/SLIDE）总 Token <= chunk.child.max-tokens：整块 1 个子块；
 * 2. 超长表格：按表格行窗口切分，每个子块前置挂载 Markdown 表头行与分隔行；
 * 3. 普通文本：按段落与句子边界累积，达到上限落盘，
 *    并回退保留末尾 overlap Token 作为下一个子块的滑窗重叠前缀；
 * 4. 单句超限时按 Token 硬切，保留重叠且不丢弃中间内容。
 */
@Component
public class ChildChunker {

    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;]+[。！？!?；;]?");

    private final TokenCounter counter;
    private final KbChunkProperties props;

    public ChildChunker(TokenCounter counter, KbChunkProperties props) {
        this.counter = counter;
        this.props = props;
    }

    public List<ChunkDraft.Child> split(ChunkDraft.Parent parent) {
        int max = props.getChild().getMaxTokens();
        if (parent.kind() != ChunkDraft.BlockKind.TEXT && parent.tokenCount() <= max) {
            return List.of(new ChunkDraft.Child(parent.content(), parent.tokenCount()));
        }
        return switch (parent.kind()) {
            case TABLE -> splitTableRows(parent.content(), max);
            case SLIDE, TEXT -> slideWindow(parent.content(), max);
        };
    }

    /** 超长表格：行窗口累计，每个子块 = 表头 + 分隔行 + 窗口数据行 */
    private List<ChunkDraft.Child> splitTableRows(String content, int max) {
        String[] lines = content.split("\n");
        if (lines.length < 3) {
            return List.of(new ChunkDraft.Child(content, counter.count(content)));
        }
        String header = lines[0];
        String separator = lines[1].contains("---") ? lines[1] : null;
        String prefix = header + "\n" + (separator == null ? "" : separator + "\n");
        int prefixTokens = counter.count(prefix);

        List<ChunkDraft.Child> result = new ArrayList<>();
        StringBuilder window = new StringBuilder();
        int windowTokens = 0;
        for (int i = separator == null ? 1 : 2; i < lines.length; i++) {
            int lineTokens = counter.count(lines[i]);
            if (windowTokens > 0 && prefixTokens + windowTokens + lineTokens > max) {
                String chunk = prefix + window;
                result.add(new ChunkDraft.Child(chunk.strip(), counter.count(chunk)));
                window.setLength(0);
                windowTokens = 0;
            }
            window.append(lines[i]).append('\n');
            windowTokens += lineTokens;
        }
        if (windowTokens > 0) {
            String chunk = prefix + window;
            result.add(new ChunkDraft.Child(chunk.strip(), counter.count(chunk)));
        }
        return result;
    }

    /** 普通文本/幻灯片：段落优先、句内滑窗，相邻子块重叠 overlap Token */
    private List<ChunkDraft.Child> slideWindow(String content, int max) {
        int overlap = props.getChild().getOverlapTokens();
        List<ChunkDraft.Child> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curTokens = 0;

        for (TextPiece piece : textPieces(content)) {
            int pieceTokens = counter.count(piece.text());
            if (pieceTokens > max) {
                if (curTokens > 0) {
                    result.add(emit(cur.toString()));
                    cur.setLength(0);
                    curTokens = 0;
                }
                result.addAll(splitOversized(piece.text(), max, overlap));
                continue;
            }

            String addition = (cur.length() == 0 ? "" : piece.separator()) + piece.text();
            int additionTokens = counter.count(addition);
            if (curTokens > 0 && curTokens + additionTokens > max) {
                String previous = cur.toString();
                result.add(emit(previous));
                String overlapText = counter.tail(previous, overlap);
                String candidate = overlapText + piece.separator() + piece.text();
                if (counter.count(candidate) <= max) {
                    cur.setLength(0);
                    cur.append(candidate);
                    curTokens = counter.count(candidate);
                } else {
                    cur.setLength(0);
                    cur.append(piece.text());
                    curTokens = pieceTokens;
                }
            } else {
                cur.append(addition);
                curTokens = counter.count(cur.toString());
            }
        }
        if (curTokens > 0) {
            result.add(emit(cur.toString()));
        }
        return result;
    }

    /** 单句超限：按 Token 硬切，保留重叠且不丢弃中间内容 */
    private List<ChunkDraft.Child> splitOversized(String text, int max, int overlap) {
        List<ChunkDraft.Child> result = new ArrayList<>();
        String rest = text;
        while (counter.count(rest) > max) {
            String head = counter.head(rest, max);
            if (head.isBlank()) {
                break;
            }
            result.add(emit(head));
            String remainder = rest.substring(head.length());
            rest = counter.tail(head, overlap) + remainder;
        }
        if (!rest.isBlank()) {
            result.add(emit(rest));
        }
        return result;
    }

    /** 段落 → 句子；段落之间保留空行分隔 */
    private List<TextPiece> textPieces(String content) {
        List<TextPiece> pieces = new ArrayList<>();
        boolean firstPiece = true;
        for (String paragraph : content.split("\\n\\s*\\n")) {
            boolean firstSentence = true;
            for (String sentence : toSentences(paragraph)) {
                String separator = firstPiece ? "" : (firstSentence ? "\n\n" : "");
                pieces.add(new TextPiece(sentence, separator));
                firstPiece = false;
                firstSentence = false;
            }
        }
        if (pieces.isEmpty() && content != null && !content.isBlank()) {
            pieces.add(new TextPiece(content.strip(), ""));
        }
        return pieces;
    }

    private record TextPiece(String text, String separator) {
    }

    private ChunkDraft.Child emit(String text) {
        String stripped = text.strip();
        return new ChunkDraft.Child(stripped, counter.count(stripped));
    }

    /** 按句子标点切分（保留句末标点） */
    private List<String> toSentences(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE.matcher(text);
        while (matcher.find()) {
            String s = matcher.group();
            if (!s.isBlank()) {
                sentences.add(s.strip());
            }
        }
        return sentences;
    }
}
