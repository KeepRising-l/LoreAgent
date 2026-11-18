package cn.keeprising.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RrfRefusalAndCitationTest {

    @Test
    void shouldFuseVectorAndKeywordHitsFavoringDualMatchedChunks() {
        UUID docId = UUID.randomUUID();
        var vec1 = new RrfFusion.ScoredChunk(1L, docId, "D1", "H", "only-vec", 0, 0.9, 0.9, 0.0);
        var vec2 = new RrfFusion.ScoredChunk(2L, docId, "D1", "H", "both", 1, 0.8, 0.8, 0.0);
        var kw1 = new RrfFusion.ScoredChunk(3L, docId, "D1", "H", "only-kw", 2, 0.7, 0.0, 0.7);
        var kw2 = new RrfFusion.ScoredChunk(2L, docId, "D1", "H", "both", 1, 0.6, 0.0, 0.6);

        List<RrfFusion.ScoredChunk> fused = new RrfFusion().fuse(List.of(vec1, vec2), List.of(kw1, kw2), 3);
        assertThat(fused).hasSize(3);
        assertThat(fused.get(0).chunkId()).isEqualTo(2L);
        assertThat(fused.get(0).vectorScore()).isEqualTo(0.8);
        assertThat(fused.get(0).keywordScore()).isEqualTo(0.6);
    }

    @Test
    void shouldRefuseEmptyOrLowSignalResultsWithClearMessage() {
        RefusalPolicy policy = new RefusalPolicy();
        assertThat(policy.evaluate(List.of(), false).refused()).isTrue();
        assertThat(policy.evaluate(List.of(), true).refusalMessage()).contains("依据不足").contains("降级");

        var weak = new RrfFusion.ScoredChunk(1L, UUID.randomUUID(), "D1", "H", "txt", 0, 0.01, 0.1, 0.0);
        assertThat(policy.evaluate(List.of(weak), false).refused()).isTrue();
    }

    @Test
    void shouldValidateCitationsAndFailWholeRunWhenCitationIndexMismatches() {
        CitationValidator validator = new CitationValidator();
        UUID docId = UUID.randomUUID();
        var c1 = new RrfFusion.ScoredChunk(10L, docId, "PG 笔记", "HNSW", "ef_search 默认 40", 0, 0.03, 0.9, 0.5);

        List<CitationValidator.Citation> ok = validator.validate("默认值是 40 [K1]。", List.of(c1), false);
        assertThat(ok).hasSize(1);
        assertThat(ok.get(0).type()).isEqualTo("kb");
        assertThat(ok.get(0).refId()).isEqualTo("K1");
        assertThat(ok.get(0).documentId()).isEqualTo(docId);
        assertThat(ok.get(0).chunkId()).isEqualTo(10L);
        assertThat(ok.get(0).title()).isEqualTo("PG 笔记");

        assertThatThrownBy(() -> validator.validate("编造了一个 [K2] 引用。", List.of(c1), false))
                .isInstanceOf(Failure.class)
                .satisfies(e -> assertThat(((Failure) e).code()).isEqualTo(Failure.ErrorCode.INVALID_CITATION));

        assertThatThrownBy(() -> validator.validate("完全不引用直接回答。", List.of(c1), false))
                .isInstanceOf(Failure.class)
                .satisfies(e -> assertThat(((Failure) e).code()).isEqualTo(Failure.ErrorCode.INVALID_CITATION));

        assertThat(validator.validate("当前资料依据不足。", List.of(c1), false)).isEmpty();
    }
}
