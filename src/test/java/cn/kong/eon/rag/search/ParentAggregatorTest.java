package cn.kong.eon.rag.search;

import cn.kong.eon.rag.repo.entity.ChildChunk;
import cn.kong.eon.rag.repo.entity.ParentChunk;
import cn.kong.eon.rag.repo.mapper.ChildChunkMapper;
import cn.kong.eon.rag.repo.mapper.ParentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 父块归并测试：得分公式（max + α×(n-1)）、预算裁剪、超长父块降级邻接窗口。
 */
class ParentAggregatorTest {

    private ParentChunkMapper parentMapper;
    private ChildChunkMapper childMapper;
    private ParentAggregator aggregator;

    @BeforeEach
    void setUp() {
        parentMapper = mock(ParentChunkMapper.class);
        childMapper = mock(ChildChunkMapper.class);
        aggregator = new ParentAggregator(parentMapper, childMapper);
    }

    private ChildChunk hit(String name, UUID parentId, int seq, double score, int tokens, String content) {
        ChildChunk c = new ChildChunk();
        c.setId(UUID.nameUUIDFromBytes(name.getBytes()));
        c.setParentId(parentId);
        c.setSeq(seq);
        c.setRawScore(score);
        c.setTokenCount(tokens);
        c.setContent(content);
        return c;
    }

    @Test
    void aggregatesByScoreFitsBudgetAndDegradesOversizedParent() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        // P1 命中 2 子块：score = 0.9（max）；P2 命中 1：0.85；P3 命中 1：0.6
        // 排序：P1 > P2 > P3（P1 的 bonus 不抵 max 差距）
        List<ChildChunk> reranked = List.of(
                hit("c1", p1, 3, 0.9, 0, "P1命中子块"),
                hit("c1b", p1, 7, 0.7, 0, "P1次命中"),
                hit("c2", p2, 1, 0.85, 300, "P2完整父块内容"),
                hit("c3", p3, 0, 0.6, 200, "P3内容"));

        ParentChunk parent1 = new ParentChunk();
        parent1.setId(p1);
        parent1.setTitle("超长父块");
        parent1.setTokenCount(1200);
        parent1.setContent("P1超长全文，必然超预算");
        ParentChunk parent2 = new ParentChunk();
        parent2.setId(p2);
        parent2.setTitle("小父块");
        parent2.setTokenCount(300);
        parent2.setContent("P2完整父块内容");
        ParentChunk parent3 = new ParentChunk();
        parent3.setId(p3);
        parent3.setTokenCount(200);
        parent3.setContent("P3内容");

        when(parentMapper.selectByIds(any())).thenReturn(List.of(parent1, parent2, parent3));
        // P1 降级：邻接窗口 [前块 150, 命中块 0 token, 后块 150] → 窗口文本 300
        ChildChunk w0 = hit("w0", p1, 2, 0, 150, "P1前邻块");
        ChildChunk w1 = hit("w1", p1, 3, 0, 0, "P1命中子块");
        ChildChunk w2 = hit("w2", p1, 4, 0, 150, "P1后邻块");
        when(childMapper.selectAdjacent(eq(p1), eq(3), eq(1), eq(1)))
                .thenReturn(List.of(w0, w1, w2));

        // 预算 500：P1 降级窗口 300（P1命中子块 tokenCount=0 估算，前+后=300）
        // 剩余 200 < P2(300) → P2 也降级（返回空窗口被丢弃）→ P3 跳过
        when(childMapper.selectAdjacent(eq(p2), eq(1), eq(1), eq(1)))
                .thenReturn(List.of());

        List<ContextSegment> segments = aggregator.aggregate(reranked, 500, 0.05);

        assertEquals(1, segments.size());
        assertEquals(1, segments.get(0).index());
        assertEquals("超长父块", segments.get(0).title());
        assertEquals("P1前邻块\n\nP1命中子块\n\nP1后邻块", segments.get(0).text());
        // hits 含该父块全部命中子块（父子两级引用定位）
        assertEquals(2, segments.get(0).hits().size());
        assertEquals(p1, segments.get(0).parentId());
    }

    @Test
    void scoreBonusRewardsMultiHitParents() {
        // P1 max=0.8 命中 4 子块 → 0.8+0.15=0.95；P2 max=0.9 命中 1 → 0.9
        // P1 排在 P2 前
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        List<ChildChunk> reranked = List.of(
                hit("a", p1, 0, 0.8, 100, "t1"),
                hit("b", p2, 0, 0.9, 100, "t2"),
                hit("c", p1, 1, 0.5, 100, "t1b"),
                hit("d", p1, 2, 0.5, 100, "t1c"),
                hit("e", p1, 3, 0.5, 100, "t1d"));

        ParentChunk parent1 = new ParentChunk();
        parent1.setId(p1);
        parent1.setTokenCount(400);
        parent1.setContent("P1内容");
        ParentChunk parent2 = new ParentChunk();
        parent2.setId(p2);
        parent2.setTokenCount(100);
        parent2.setContent("P2内容");
        when(parentMapper.selectByIds(any())).thenReturn(List.of(parent1, parent2));

        List<ContextSegment> segments = aggregator.aggregate(reranked, 2000, 0.05);

        assertEquals(2, segments.size());
        assertEquals(p1, segments.get(0).parentId());
        assertEquals(4, segments.get(0).hits().size());
    }

    @Test
    void fallbackWithoutBonusKeepsSinglePrecisionHitOnTop() {
        // P2 命中 1 个 rank1(1/61)，P1 命中 2 个 rank20(2/80)
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        double rank1 = 1.0 / 61;
        double rank20 = 1.0 / 80;
        List<ChildChunk> reranked = List.of(
                hit("a", p1, 0, rank20, 100, "t1"),
                hit("b", p1, 1, rank20, 100, "t1b"),
                hit("c", p2, 0, rank1, 100, "t2"));

        ParentChunk parent1 = new ParentChunk();
        parent1.setId(p1);
        parent1.setTokenCount(100);
        parent1.setContent("P1内容");
        ParentChunk parent2 = new ParentChunk();
        parent2.setId(p2);
        parent2.setTokenCount(100);
        parent2.setContent("P2内容");
        when(parentMapper.selectByIds(any())).thenReturn(List.of(parent1, parent2));

        List<ContextSegment> fallback = aggregator.aggregate(reranked, 2000, 0.0);
        assertEquals(p2, fallback.get(0).parentId());

        List<ContextSegment> withBonus = aggregator.aggregate(reranked, 2000, 0.05);
        assertEquals(p1, withBonus.get(0).parentId());
    }
}
