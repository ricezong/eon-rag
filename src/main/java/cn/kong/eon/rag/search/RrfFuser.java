package cn.kong.eon.rag.search;

import cn.kong.eon.rag.repo.entity.ChildChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RRF 倒数秩融合：Score(d) = Σ 1/(k + rank_m(d))，仅依赖通道内排名，
 * 消除余弦/词频/三元组三路分数量纲差异。
 */
@Component
public class RrfFuser {

    private static final Logger log = LoggerFactory.getLogger(RrfFuser.class);

    /** 融合三路通道，按 id 去重取前 topN（各通道排名从 1 开始） */
    public List<ChildChunk> fuse(List<List<ChildChunk>> channels, int k, int topN) {
        Map<UUID, Double> scores = new HashMap<>();
        Map<UUID, ChildChunk> byId = new HashMap<>();

        for (List<ChildChunk> channel : channels) {
            for (int i = 0; i < channel.size(); i++) {
                ChildChunk child = channel.get(i);
                scores.merge(child.getId(), 1.0 / (k + i + 1), Double::sum);
                byId.putIfAbsent(child.getId(), child);
            }
        }

        List<Map.Entry<UUID, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());

        List<ChildChunk> result = new ArrayList<>();
        for (Map.Entry<UUID, Double> entry : ranked) {
            if (result.size() >= topN) {
                break;
            }
            ChildChunk child = byId.get(entry.getKey());
            child.setRawScore(entry.getValue());
            result.add(child);
        }
        log.debug("rrf fused unique={} returned={} top={}", scores.size(), result.size(),
                result.stream().limit(10)
                        .map(item -> item.getId() + ":" + item.getRawScore())
                        .toList());
        return result;
    }
}
