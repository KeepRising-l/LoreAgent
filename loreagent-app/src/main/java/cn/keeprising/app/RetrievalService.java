package cn.keeprising.app;

import cn.keeprising.core.EmbeddingClient;
import cn.keeprising.core.Failure;
import cn.keeprising.core.RrfFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 混合检索协调服务：并发/双路拉取向量与关键词候选后用 RRF 融合；当 Embedding 异常时自动降级为仅关键词检索并向上透传降级状态。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private static final int DEFAULT_TOP_K = 5;

    private final EmbeddingClient embeddingClient;
    private final VectorSearch vectorSearch;
    private final KeywordSearch keywordSearch;
    private final RrfFusion rrfFusion;

    public RetrievalService(
            EmbeddingClient embeddingClient,
            VectorSearch vectorSearch,
            KeywordSearch keywordSearch,
            RrfFusion rrfFusion) {
        this.embeddingClient = embeddingClient;
        this.vectorSearch = vectorSearch;
        this.keywordSearch = keywordSearch;
        this.rrfFusion = rrfFusion;
    }

    public RetrievalResult retrieve(String userId, String query, Scope scope, Integer requestedTopK) {
        if (query == null || query.isBlank()) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "检索 query 不能为空");
        }
        int topK = (requestedTopK == null || requestedTopK <= 0) ? DEFAULT_TOP_K : Math.min(requestedTopK, 20);
        int perPathLimit = Math.max(topK * 3, 15);
        String ownerFilter = scope == null ? null : scope.owner();
        List<String> tagFilters = scope == null ? List.of() : scope.tags();

        List<RrfFusion.ScoredChunk> vectorHits = List.of();
        boolean degraded = false;
        String degradedReason = null;

        if (!embeddingClient.isConfigured()) {
            degraded = true;
            degradedReason = "Embedding 服务未配置，已使用关键词检索";
        } else {
            try {
                float[] queryVec = embeddingClient.embedQuery(query);
                vectorHits = vectorSearch.search(
                        userId, queryVec, embeddingClient.modelName(), ownerFilter, tagFilters, perPathLimit);
            } catch (Failure f) {
                degraded = true;
                degradedReason = "向量检索接口异常（" + f.getMessage() + "），已自动降级为关键词检索";
                log.warn("event=retrieval_embedding_degraded code={} reason={}", f.code(), f.getMessage());
            }
        }

        List<RrfFusion.ScoredChunk> keywordHits = keywordSearch.search(
                userId, query, ownerFilter, tagFilters, perPathLimit);
        List<RrfFusion.ScoredChunk> fused = rrfFusion.fuse(vectorHits, keywordHits, topK);
        return new RetrievalResult(fused, degraded, degradedReason);
    }

    public record Scope(String owner, List<String> tags) {
    }

    public record RetrievalResult(
            List<RrfFusion.ScoredChunk> chunks,
            boolean degraded,
            String degradedReason) {
    }
}
