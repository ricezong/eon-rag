package cn.kong.eon.rag.chunk;

import cn.kong.eon.rag.config.properties.KbChunkProperties;
import cn.kong.eon.rag.parse.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分块层测试：父块切分规则 + 子块滑窗/表头重放（真实 jtokkit 计数）。
 */
class ChunkersTest {

    private TokenCounter counter;
    private KbChunkProperties props;
    private ParentChunker parentChunker;
    private ChildChunker childChunker;

    @BeforeEach
    void setUp() {
        counter = new JtokkitTokenCounter();
        props = new KbChunkProperties();
        parentChunker = new ParentChunker(counter, props);
        childChunker = new ChildChunker(counter, props);
    }

    /** 构造超过父块尾块最小阈值（200 Token）的长文本，避免触发尾块合并。
     *  CL100K 中文约 1.1 token/字：21 字 × 12 ≈ 276 token，实测验证 */
    private static String longParagraph(String seed) {
        return seed.repeat(12);
    }

    @Test
    void headingOpensNewParentAndRecordsTitle() {
        List<Block> blocks = List.of(
                new Block(Block.BlockType.HEADING, 1, "系统概述", 0, 0, Map.of()),
                new Block(Block.BlockType.PARAGRAPH, 0, longParagraph("系统概述章节的正文内容，用于超过尾块阈值。"), 0, 0, Map.of()),
                new Block(Block.BlockType.HEADING, 1, "部署要求", 0, 0, Map.of()),
                new Block(Block.BlockType.PARAGRAPH, 0, longParagraph("部署要求章节的正文内容，用于超过尾块阈值。"), 0, 0, Map.of()));
        List<ChunkDraft.Parent> parents = parentChunker.split(blocks);
        assertEquals(2, parents.size());
        assertEquals("系统概述", parents.get(0).title());
        assertEquals("部署要求", parents.get(1).title());
        assertTrue(parents.get(0).content().contains("系统概述章节"));
    }

    /** 连续标题之间没有正文时，前一个标题并入下一个父块，不产生只含标题的父块 */
    @Test
    void headingChainWithoutBodyJoinsNextSection() {
        List<Block> blocks = List.of(
                new Block(Block.BlockType.HEADING, 1, "总标题", 0, 0, Map.of()),
                new Block(Block.BlockType.HEADING, 2, "第一节", 0, 0, Map.of()),
                new Block(Block.BlockType.PARAGRAPH, 0,
                        longParagraph("第一节正文内容，用于超过尾块阈值。"), 0, 0, Map.of()));
        List<ChunkDraft.Parent> parents = parentChunker.split(blocks);
        assertEquals(1, parents.size());
        assertEquals("第一节", parents.get(0).title());
        assertTrue(parents.get(0).content().contains("总标题"));
        assertTrue(parents.get(0).content().contains("第一节正文内容"));
    }

    @Test
    void tableNeverMixedWithText() {
        List<Block> blocks = List.of(
                new Block(Block.BlockType.PARAGRAPH, 0, longParagraph("表格之前的正文段落，长度超过尾块阈值。"), 0, 0, Map.of()),
                new Block(Block.BlockType.TABLE, 0, "| A | B |\n| --- | --- |\n| 1 | 2 |", 0, 0, Map.of()),
                new Block(Block.BlockType.PARAGRAPH, 0, longParagraph("表格之后的正文段落，长度超过尾块阈值。"), 0, 0, Map.of()));
        List<ChunkDraft.Parent> parents = parentChunker.split(blocks);
        assertEquals(3, parents.size());
        assertEquals(ChunkDraft.BlockKind.TABLE, parents.get(1).kind());
        assertTrue(parents.get(0).content().contains("表格之前"));
        assertTrue(parents.get(2).content().contains("表格之后"));
    }

    @Test
    void shortTailMergesIntoPreviousTextParent() {
        List<Block> blocks = List.of(
                new Block(Block.BlockType.PARAGRAPH, 0, longParagraph("第一个长正文段落，超过尾块阈值，保持独立成块。"), 0, 0, Map.of()),
                new Block(Block.BlockType.PARAGRAPH, 0, "短尾块。", 0, 0, Map.of()));
        List<ChunkDraft.Parent> parents = parentChunker.split(blocks);
        assertEquals(1, parents.size());
        assertTrue(parents.get(0).content().contains("短尾块"));
    }

    /** 尾块与前一文本父块之间隔着表格时不得合并，否则内容越过表格造成顺序错位 */
    @Test
    void shortTailKeepsOrderWhenTableInBetween() {
        List<Block> blocks = List.of(
                new Block(Block.BlockType.PARAGRAPH, 0, longParagraph("表格之前的正文段落，超过尾块阈值。"), 0, 0, Map.of()),
                new Block(Block.BlockType.TABLE, 0, "| A | B |\n| --- | --- |\n| 1 | 2 |", 0, 0, Map.of()),
                new Block(Block.BlockType.PARAGRAPH, 0, "表格之后的短尾块。", 0, 0, Map.of()));
        List<ChunkDraft.Parent> parents = parentChunker.split(blocks);
        assertEquals(3, parents.size());
        assertEquals(ChunkDraft.BlockKind.TABLE, parents.get(1).kind());
        assertTrue(parents.get(2).content().contains("表格之后的短尾块"));
        assertTrue(!parents.get(0).content().contains("表格之后的短尾块"));
    }

    @Test
    void oversizedTableSplitWithHeaderReplay() {
        // 构造超过 1200 Token 的大表：120 行
        StringBuilder table = new StringBuilder("| 编号 | 名称 | 说明 |\n| --- | --- | --- |\n");
        for (int i = 0; i < 120; i++) {
            table.append("| ").append(i).append(" | 产品型号编号第").append(i).append("号 | 该行详细描述信息用于增加内容长度参数说明 | \n");
        }
        List<Block> blocks = List.of(new Block(Block.BlockType.TABLE, 0, table.toString(), 0, 0, Map.of()));
        List<ChunkDraft.Parent> parents = parentChunker.split(blocks);
        assertTrue(parents.size() >= 2, "oversized table should split into multiple parents");
        for (ChunkDraft.Parent parent : parents) {
            assertTrue(parent.content().startsWith("| 编号 | 名称 | 说明 |"),
                    "each split parent must replay header");
            assertTrue(parent.tokenCount() <= props.getParent().getMaxTokens());
        }
    }

    @Test
    void childChunksNeverExceedLimitAndCarryOverlap() {
        String longText = "这是一个用于测试滑窗切分的句子，包含标点符号。".repeat(60);
        ChunkDraft.Parent parent = new ChunkDraft.Parent("标题", ChunkDraft.BlockKind.TEXT,
                longText, counter.count(longText), Map.of());
        List<ChunkDraft.Child> children = childChunker.split(parent);
        assertTrue(children.size() >= 2);
        for (ChunkDraft.Child child : children) {
            assertTrue(child.tokenCount() <= props.getChild().getMaxTokens());
        }
        String firstTail = children.get(0).content()
                .substring(children.get(0).content().length() - 12);
        assertTrue(children.get(1).content().contains(firstTail),
                "second chunk must start with overlap prefix of first chunk");
    }

    @Test
    void oversizedSentenceDoesNotLoseContent() {
        String text = "开头标记" + "甲".repeat(700) + "中间标记" + "乙".repeat(700) + "结尾标记";
        ChunkDraft.Parent parent = new ChunkDraft.Parent(null, ChunkDraft.BlockKind.TEXT,
                text, counter.count(text), Map.of());
        List<ChunkDraft.Child> children = childChunker.split(parent);
        assertTrue(children.size() > 1);
        for (ChunkDraft.Child child : children) {
            assertTrue(child.tokenCount() <= props.getChild().getMaxTokens());
        }
        String joined = String.join("", children.stream().map(ChunkDraft.Child::content).toList());
        assertTrue(joined.contains("开头标记"));
        assertTrue(joined.contains("中间标记"));
        assertTrue(joined.contains("结尾标记"));
    }

    @Test
    void paragraphBoundaryIsPreserved() {
        String text = "第一段内容。".repeat(20) + "\n\n" + "第二段内容。".repeat(20);
        ChunkDraft.Parent parent = new ChunkDraft.Parent(null, ChunkDraft.BlockKind.TEXT,
                text, counter.count(text), Map.of());
        List<ChunkDraft.Child> children = childChunker.split(parent);
        assertTrue(children.stream().anyMatch(child -> child.content().contains("\n\n")));
    }

    @Test
    void smallAtomicParentBecomesSingleChild() {
        ChunkDraft.Parent table = new ChunkDraft.Parent(null, ChunkDraft.BlockKind.TABLE,
                "| A | B |\n| --- | --- |\n| 1 | 2 |", counter.count("| A | B |"), Map.of());
        List<ChunkDraft.Child> children = childChunker.split(table);
        assertEquals(1, children.size());
        assertEquals(table.content(), children.get(0).content());
    }

    @Test
    void longTableChildReplaysHeader() {
        StringBuilder table = new StringBuilder("| 编号 | 名称 |\n| --- | --- |\n");
        for (int i = 0; i < 40; i++) {
            table.append("| ").append(i).append(" | 产品编号描述第").append(i).append("号超长名称信息 | \n");
        }
        ChunkDraft.Parent parent = new ChunkDraft.Parent(null, ChunkDraft.BlockKind.TABLE,
                table.toString(), counter.count(table.toString()), Map.of());
        List<ChunkDraft.Child> children = childChunker.split(parent);
        assertTrue(children.size() >= 2);
        for (ChunkDraft.Child child : children) {
            assertTrue(child.content().startsWith("| 编号 | 名称 |"));
        }
    }

    @Test
    void textParentCarriesPageRange() {
        List<Block> blocks = List.of(
                new Block(Block.BlockType.HEADING, 1, "系统概述", 3, 3, Map.of()),
                new Block(Block.BlockType.PARAGRAPH, 0, longParagraph("系统概述章节的正文内容，用于超过尾块阈值。"), 4, 4, Map.of()),
                new Block(Block.BlockType.PARAGRAPH, 0, longParagraph("系统概述续写正文内容，用于超过尾块阈值。"), 5, 5, Map.of()));
        List<ChunkDraft.Parent> parents = parentChunker.split(blocks);
        assertEquals(1, parents.size());
        assertEquals("3", parents.get(0).meta().get("page_start"));
        assertEquals("5", parents.get(0).meta().get("page_end"));
    }

    @Test
    void parentWithoutPageConceptOmitsPageRange() {
        List<Block> blocks = List.of(
                new Block(Block.BlockType.HEADING, 1, "系统概述", 0, 0, Map.of()),
                new Block(Block.BlockType.PARAGRAPH, 0, longParagraph("系统概述章节的正文内容，用于超过尾块阈值。"), 0, 0, Map.of()));
        List<ChunkDraft.Parent> parents = parentChunker.split(blocks);
        assertEquals(1, parents.size());
        assertTrue(parents.get(0).meta().isEmpty());
    }

    @Test
    void shortTailParentMergesWithUnionPageRange() {
        List<Block> blocks = List.of(
                new Block(Block.BlockType.HEADING, 1, "第一章", 1, 1, Map.of()),
                new Block(Block.BlockType.PARAGRAPH, 0, longParagraph("第一章正文内容，用于超过尾块阈值。"), 2, 2, Map.of()),
                new Block(Block.BlockType.HEADING, 1, "第二章", 3, 3, Map.of()),
                new Block(Block.BlockType.PARAGRAPH, 0, "本章内容简述。", 3, 3, Map.of()));
        List<ChunkDraft.Parent> parents = parentChunker.split(blocks);
        assertEquals(1, parents.size());
        assertEquals("1", parents.get(0).meta().get("page_start"));
        assertEquals("3", parents.get(0).meta().get("page_end"));
    }
}
