package cn.kong.eon.rag.search;

import cn.kong.eon.rag.repo.entity.ChildChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合测试：跨通道排名聚合、去重、TopN。
 */
class RrfFuserTest {

    private final RrfFuser fuser = new RrfFuser();

    private ChildChunk child(String id) {
        ChildChunk c = new ChildChunk();
        c.setId(UUID.nameUUIDFromBytes(id.getBytes()));
        return c;
    }

    @Test
    void fusesRanksAcrossChannelsAndDeduplicates() {
        ChildChunk c1 = child("c1");
        ChildChunk c2 = child("c2");
        ChildChunk c3 = child("c3");
        ChildChunk c4 = child("c4");

        // c1 两通道均排名第一 → 融合分最高
        List<ChildChunk> fused = fuser.fuse(List.of(
                List.of(c1, c2, c3),
                List.of(c1, c4, c2)), 60, 50);

        assertEquals(4, fused.size());
        assertEquals(c1.getId(), fused.get(0).getId());
        // 融合分 = 2/(60+1)
        assertEquals(2.0 / 61, fused.get(0).getRawScore(), 1e-9);
    }

    @Test
    void respectsTopN() {
        List<ChildChunk> fused = fuser.fuse(List.of(
                List.of(child("a"), child("b"), child("c"))), 60, 2);
        assertEquals(2, fused.size());
    }
}
