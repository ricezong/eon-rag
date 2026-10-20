package cn.kong.eon.rag.chunk;

import cn.kong.eon.rag.config.properties.KbChunkProperties;
import cn.kong.eon.rag.parse.Block;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 父块切分器：按章节聚合，表格/幻灯片独占，超限切断，尾块合并。
 *
 * 规则：
 * 1. level <= chunk.parent.heading-level 的 HEADING 结束上一父块并开启新父块，标题记为 title；
 *    连续标题之间没有正文时，前一个标题并入新父块开头，不单独成块；
 * 2. 普通文本块（PARAGRAPH/LIST/CODE，以及 level 大于阈值的子标题）累加进当前父块；
 * 3. 文本累加超 chunk.parent.max-tokens 时在当前位置切断，新父块沿用最近章节标题；
 * 4. TABLE/SLIDE 独占父块；标题后紧跟原子块时标题下传给原子块；
 * 5. 尾块不足 chunk.parent.min-tokens 并入紧邻的前一个文本父块，中间隔着原子块则不合并。
 */
@Component
public class ParentChunker {

    private final TokenCounter counter;
    private final KbChunkProperties props;

    public ParentChunker(TokenCounter counter, KbChunkProperties props) {
        this.counter = counter;
        this.props = props;
    }

    public List<ChunkDraft.Parent> split(List<Block> blocks) {
        List<ChunkDraft.Parent> result = new ArrayList<>();
        MutableParent current = null;
        // 当前父块是否已有正文（决定连续标题是否并入、原子块是否继承标题）
        boolean currentHasBody = false;
        String lastHeading = "";

        for (Block block : blocks) {
            if (block.type() == Block.BlockType.HEADING && block.level() > 0
                    && block.level() <= props.getParent().getHeadingLevel()) {
                // 规则 1：章节标题开新父块；标题文本进 content 开头以保标题词可检索
                String heading = block.text().strip();
                String carried = "";
                if (current != null) {
                    if (currentHasBody) {
                        result.add(current.toDraft(counter));
                    } else {
                        carried = current.content.toString().strip();
                    }
                }
                lastHeading = heading;
                current = new MutableParent(lastHeading);
                if (!carried.isEmpty()) {
                    current.append(carried, counter.count(carried));
                }
                current.append(heading, counter.count(heading));
                current.includePage(block);
                currentHasBody = false;
            } else if (block.isAtomic()) {
                // 标题后紧跟原子块时标题下传，避免产生只含标题的碎片父块
                String atomicTitle = null;
                if (current != null) {
                    if (currentHasBody) {
                        result.add(current.toDraft(counter));
                    } else {
                        atomicTitle = current.title;
                    }
                    current = null;
                    currentHasBody = false;
                }
                splitAtomic(block, result, atomicTitle);
            } else {
                // 规则 2/3：普通文本累加，超限切断并沿用最近章节标题
                String text = block.type() == Block.BlockType.HEADING
                        ? "#".repeat(block.level()) + " " + block.text().strip()
                        : block.text().strip();
                if (text.isEmpty()) {
                    continue;
                }
                int textTokens = counter.count(text);
                if (current == null) {
                    current = new MutableParent(lastHeading);
                } else if (current.tokenCount + textTokens > props.getParent().getMaxTokens()) {
                    result.add(current.toDraft(counter));
                    current = new MutableParent(lastHeading);
                }
                current.append(text, textTokens);
                current.includePage(block);
                currentHasBody = true;
            }
        }
        if (current != null) {
            result.add(current.toDraft(counter));
        }
        mergeShortTail(result);
        return result;
    }

    /** 表格/幻灯片独占父块；超长表格按行窗口拆分并重放表头与分隔行 */
    private void splitAtomic(Block block, List<ChunkDraft.Parent> result, String title) {
        int max = props.getParent().getMaxTokens();
        if (block.type() == Block.BlockType.SLIDE) {
            if (counter.count(block.text()) <= max) {
                result.add(atomicDraft(ChunkDraft.BlockKind.SLIDE, block.text(), block.meta(), title));
                return;
            }
            // 单页超限：按段落边界切分，保留原 meta
            for (String part : splitByParagraphBoundaries(block.text(), max)) {
                result.add(atomicDraft(ChunkDraft.BlockKind.SLIDE, part, block.meta(), title));
            }
            return;
        }

        // TABLE：解析 Markdown 行
        String[] lines = block.text().split("\n");
        if (lines.length < 2 || counter.count(block.text()) <= max) {
            result.add(atomicDraft(ChunkDraft.BlockKind.TABLE, block.text(), block.meta(), title));
            return;
        }
        String header = lines[0];
        String separator = lines[1].contains("---") ? lines[1] : null;
        int headTokens = counter.count(header) + (separator == null ? 0 : counter.count(separator));
        StringBuilder window = new StringBuilder();
        int windowTokens = 0;
        for (int i = separator == null ? 1 : 2; i < lines.length; i++) {
            int lineTokens = counter.count(lines[i]);
            if (windowTokens > 0 && headTokens + windowTokens + lineTokens > max) {
                result.add(tableWindow(header, separator, window.toString(), block.meta(), title));
                window.setLength(0);
                windowTokens = 0;
            }
            window.append(lines[i]).append('\n');
            windowTokens += lineTokens;
        }
        if (windowTokens > 0) {
            result.add(tableWindow(header, separator, window.toString(), block.meta(), title));
        }
    }

    private ChunkDraft.Parent tableWindow(String header, String separator, String body,
                                          Map<String, String> meta, String title) {
        String content = header + "\n" + (separator == null ? "" : separator + "\n") + body;
        return new ChunkDraft.Parent(title, ChunkDraft.BlockKind.TABLE, content.strip(),
                counter.count(content), meta == null ? Map.of() : Map.copyOf(meta));
    }

    private List<String> splitByParagraphBoundaries(String text, int max) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curTokens = 0;
        for (String para : text.split("\n\\s*\n")) {
            int tokens = counter.count(para);
            if (curTokens > 0 && curTokens + tokens > max) {
                parts.add(cur.toString().strip());
                cur.setLength(0);
                curTokens = 0;
            }
            cur.append(para).append("\n\n");
            curTokens += tokens;
        }
        if (curTokens > 0) {
            parts.add(cur.toString().strip());
        }
        return parts;
    }

    /** 尾块不足 min-tokens 并入紧邻的前一个文本父块；中间隔着原子块则不合并 */
    private void mergeShortTail(List<ChunkDraft.Parent> result) {
        if (result.size() < 2) {
            return;
        }
        ChunkDraft.Parent last = result.get(result.size() - 1);
        if (last.kind() != ChunkDraft.BlockKind.TEXT
                || last.tokenCount() >= props.getParent().getMinTokens()) {
            return;
        }
        ChunkDraft.Parent prev = result.get(result.size() - 2);
        if (prev.kind() != ChunkDraft.BlockKind.TEXT) {
            return;
        }
        String content = prev.content() + "\n\n" + last.content();
        result.set(result.size() - 2, new ChunkDraft.Parent(prev.title(), prev.kind(), content,
                counter.count(content), unionPageRange(prev.meta(), last.meta())));
        result.remove(result.size() - 1);
    }

    /** 合并两个父块的页码范围 */
    private static Map<String, String> unionPageRange(Map<String, String> prev, Map<String, String> tail) {
        int start = pageOf(prev, "page_start");
        int tailStart = pageOf(tail, "page_start");
        if (tailStart > 0 && (start <= 0 || tailStart < start)) {
            start = tailStart;
        }
        if (start <= 0) {
            return prev;
        }
        int end = Math.max(pageOf(prev, "page_end"), pageOf(tail, "page_end"));
        Map<String, String> merged = new LinkedHashMap<>(prev);
        merged.put("page_start", String.valueOf(start));
        merged.put("page_end", String.valueOf(Math.max(end, start)));
        return Map.copyOf(merged);
    }

    private static int pageOf(Map<String, String> meta, String key) {
        String value = meta.get(key);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 页码范围并入 meta */
    private static Map<String, String> withPageRange(Map<String, String> meta, int startPage, int endPage) {
        if (startPage <= 0) {
            return meta;
        }
        Map<String, String> merged = new LinkedHashMap<>(meta);
        merged.put("page_start", String.valueOf(startPage));
        merged.put("page_end", String.valueOf(Math.max(endPage, startPage)));
        return Map.copyOf(merged);
    }

    private ChunkDraft.Parent atomicDraft(ChunkDraft.BlockKind kind, String content,
                                          Map<String, String> meta, String title) {
        return new ChunkDraft.Parent(title, kind, content, counter.count(content),
                meta == null ? Map.of() : Map.copyOf(meta));
    }

    /** 文本父块累积器，避免频繁字符串拷贝 */
    private static final class MutableParent {
        final String title;
        final StringBuilder content = new StringBuilder();
        int tokenCount;
        // 父块页码范围；0 表示无页概念
        int startPage;
        int endPage;

        MutableParent(String title) {
            this.title = title;
        }

        void append(String text, int tokens) {
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append(text);
            tokenCount += tokens;
        }

        /** 累积块所在页码 */
        void includePage(Block block) {
            if (block.startPage() > 0 && (startPage == 0 || block.startPage() < startPage)) {
                startPage = block.startPage();
            }
            if (block.endPage() > endPage) {
                endPage = block.endPage();
            }
        }

        ChunkDraft.Parent toDraft(TokenCounter counter) {
            String text = content.toString();
            return new ChunkDraft.Parent(title, ChunkDraft.BlockKind.TEXT, text,
                    counter.count(text), withPageRange(Map.of(), startPage, endPage));
        }
    }
}
