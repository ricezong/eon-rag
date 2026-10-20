package cn.kong.eon.rag.search;

import cn.kong.eon.rag.repo.entity.ChildChunk;
import cn.kong.eon.rag.repo.entity.ParentChunk;
import cn.kong.eon.rag.repo.mapper.ChildChunkMapper;
import cn.kong.eon.rag.repo.mapper.ParentChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 父块上卷归并与动态上下文预算裁剪。
 *
 * parent_score = max(子块重排分) + α×(命中数-1)，α 默认 0.05；
 * 按 parent_score 降序依次装入 Token 预算：
 *   - 父块完整装入：剩余预算递减；
 *   - 单个父块超剩余预算：降级为「命中子块 ± 邻接子块」局部窗口；
 *   - 预算耗尽即停止（保证高分父块优先完整呈现）。
 */
@Component
public class ParentAggregator {

    private static final Logger log = LoggerFactory.getLogger(ParentAggregator.class);

    private final ParentChunkMapper parentChunkMapper;
    private final ChildChunkMapper childChunkMapper;

    public ParentAggregator(ParentChunkMapper parentChunkMapper,
                            ChildChunkMapper childChunkMapper) {
        this.parentChunkMapper = parentChunkMapper;
        this.childChunkMapper = childChunkMapper;
    }

    /**
     * @param reranked        重排后的命中子块（带 rawScore）
     * @param budgetTokens    上下文总预算（max-context-tokens - output-reserve - 问题与模板开销）
     * @param hitBonusWeight  命中数加分权重 α
     */
    public List<ContextSegment> aggregate(List<ChildChunk> reranked, int budgetTokens,
                                          double hitBonusWeight) {
        if (reranked.isEmpty() || budgetTokens <= 0) {
            return List.of();
        }
        // 1. 按 parentId 分组并计算父块综合得分
        Map<UUID, List<ChildChunk>> byParent = new LinkedHashMap<>();
        for (ChildChunk child : reranked) {
            byParent.computeIfAbsent(child.getParentId(), v -> new ArrayList<>()).add(child);
        }
        record Scored(UUID parentId, double score, List<ChildChunk> hits) {
        }
        List<Scored> scored = byParent.entrySet().stream()
                .map(e -> {
                    double max = e.getValue().stream()
                            .mapToDouble(ChildChunk::getRawScore).max().orElse(0);
                    double bonus = hitBonusWeight * (e.getValue().size() - 1);
                    return new Scored(e.getKey(), max + bonus, e.getValue());
                })
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .toList();

        // 2. 批量加载父块文本
        Map<UUID, ParentChunk> parents = new LinkedHashMap<>();
        for (ParentChunk p : parentChunkMapper.selectByIds(scored.stream().map(Scored::parentId).toList())) {
            parents.put(p.getId(), p);
        }
        log.debug("aggregate grouped candidateParents={} loadedParents={} budgetTokens={}",
                scored.size(), parents.size(), budgetTokens);

        // 3. 依序装入预算，超限父块降级邻接窗口
        List<ContextSegment> segments = new ArrayList<>();
        int remaining = budgetTokens;
        int index = 1;
        int degraded = 0;
        for (Scored s : scored) {
            if (remaining <= 0) {
                break;
            }
            ParentChunk parent = parents.get(s.parentId());
            if (parent == null) {
                continue;
            }
            List<ContextSegment.ChildRef> refs = s.hits().stream()
                    .map(c -> new ContextSegment.ChildRef(c.getId(), c.getSeq(), c.getRawScore()))
                    .toList();

            if (parent.getTokenCount() <= remaining) {
                segments.add(new ContextSegment(index++, parent.getTitle(), parent.getDocumentId(),
                        parent.getId(), parent.getContent(), parent.getTokenCount(), parent.getMeta(), refs));
                remaining -= parent.getTokenCount();
            } else {
                WindowResult window = degradeToWindow(parent, s.hits(), remaining);
                if (window != null) {
                    degraded++;
                    segments.add(new ContextSegment(index++, parent.getTitle(), parent.getDocumentId(),
                            parent.getId(), window.text(), window.tokens(), parent.getMeta(), refs));
                    remaining -= window.tokens();
                }
            }
        }
        log.debug("aggregate done candidateParents={} segments={} degraded={} usedTokens={} remaining={}",
                scored.size(), segments.size(), degraded,
                Math.max(0, budgetTokens - remaining), Math.max(0, remaining));
        return segments;
    }

    /** 邻接窗口产物：文本 + 精确 Token 用量（优先取子块 token_count，缺省时估算） */
    private record WindowResult(String text, int tokens) {
    }

    /**
     * 降级：取父块内得分最高的命中子块，加载 seq±1 邻接窗口，
     * 拼接文本不超过剩余预算则返回，否则返回 null（丢弃该父块）。
     */
    private WindowResult degradeToWindow(ParentChunk parent, List<ChildChunk> hits, int remaining) {
        ChildChunk best = hits.stream()
                .max(Comparator.comparingDouble(ChildChunk::getRawScore))
                .orElse(null);
        if (best == null) {
            return null;
        }
        List<ChildChunk> window = childChunkMapper.selectAdjacent(
                parent.getId(), best.getSeq(), 1, 1);
        if (window == null || window.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int tokens = 0;
        for (ChildChunk child : window) {
            Integer counted = child.getTokenCount();
            int t = counted == null || counted == 0
                    ? estimateTokens(child.getContent()) : counted;
            if (tokens + t > remaining) {
                break;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(child.getContent());
            tokens += t;
        }
        return sb.isEmpty() ? null : new WindowResult(sb.toString(), tokens);
    }

    /** 无 token_count 元数据时的降级估算：中文 × 1.5 + 其它 × 0.25 */
    private int estimateTokens(String text) {
        if (text == null) {
            return 0;
        }
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            tokens += text.charAt(i) > 0x2E7F ? 1.5 : 0.25;
        }
        return (int) Math.ceil(tokens);
    }
}
