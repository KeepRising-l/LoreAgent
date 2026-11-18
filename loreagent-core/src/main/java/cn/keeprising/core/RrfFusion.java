package cn.keeprising.core;

import java.util.*;

/**
 * 倒数排名融合（Reciprocal Rank Fusion）：只看两路召回的名次 1 / (k + rank)，不直接相加不同量纲的原始分数。
 * 同时承载统一检索候选结构 ScoredChunk，供 VectorSearch、KeywordSearch、RefusalPolicy 与 CitationValidator 共用。
 */
public class RrfFusion {

    private static final int DEFAULT_K = 60;

    private final int k;

    public RrfFusion() {
        this(DEFAULT_K);
    }

    public RrfFusion(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("RRF 平滑常数 k 必须大于 0");
        }
        this.k = k;
    }

    public List<ScoredChunk> fuse(List<ScoredChunk> vectorHits, List<ScoredChunk> keywordHits, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        Map<Long, MergedCandidate> merged = new LinkedHashMap<>();
        accumulate(merged, vectorHits, true);
        accumulate(merged, keywordHits, false);

        return merged.values().stream()
                .map(MergedCandidate::toScoredChunk)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparingLong(ScoredChunk::chunkId))
                .limit(topK)
                .toList();
    }

    private void accumulate(Map<Long, MergedCandidate> merged, List<ScoredChunk> hits, boolean isVector) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            ScoredChunk hit = hits.get(i);
            double contribution = 1.0 / (k + (i + 1));
            MergedCandidate acc = merged.computeIfAbsent(hit.chunkId(), id -> new MergedCandidate(hit));
            acc.rrfScore += contribution;
            if (isVector) {
                acc.vectorScore = Math.max(acc.vectorScore, hit.vectorScore());
            } else {
                acc.keywordScore = Math.max(acc.keywordScore, hit.keywordScore());
            }
        }
    }

    private static final class MergedCandidate {
        private final ScoredChunk base;
        private double rrfScore;
        private double vectorScore;
        private double keywordScore;

        private MergedCandidate(ScoredChunk base) {
            this.base = base;
        }

        private ScoredChunk toScoredChunk() {
            return new ScoredChunk(
                    base.chunkId(), base.documentId(), base.docTitle(), base.heading(),
                    base.content(), base.chunkIndex(), rrfScore, vectorScore, keywordScore);
        }
    }

    public record ScoredChunk(
            long chunkId,
            UUID documentId,
            String docTitle,
            String heading,
            String content,
            int chunkIndex,
            double score,
            double vectorScore,
            double keywordScore) {
    }
}
