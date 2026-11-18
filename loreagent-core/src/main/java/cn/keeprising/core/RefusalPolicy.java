package cn.keeprising.core;

import java.util.List;

/**
 * 拒答门控策略：当知识库未检索到任何相关片段，或最高相关性仍低于可靠下限时，直接拒答并明确输出“依据不足”，防止模型硬编。
 */
public class RefusalPolicy {

    private static final double DEFAULT_MIN_VECTOR_SCORE = 0.25;

    private final double minVectorScore;

    public RefusalPolicy() {
        this(DEFAULT_MIN_VECTOR_SCORE);
    }

    public RefusalPolicy(double minVectorScore) {
        this.minVectorScore = minVectorScore;
    }

    public Decision evaluate(List<RrfFusion.ScoredChunk> candidates, boolean degraded) {
        if (candidates == null || candidates.isEmpty()) {
            String msg = degraded
                    ? "向量检索当前不可用，已降级为关键词检索；知识库中未检索到与当前问题相关的内容，依据不足，无法回答该问题。"
                    : "知识库中未检索到与当前问题相关的内容，依据不足，无法回答该问题。";
            return new Decision(true, msg);
        }
        // 非降级模式下：若所有片段既无关键词命中，余弦相似度又全部低于阈值，同样判定依据不足
        if (!degraded) {
            boolean hasSignal = candidates.stream()
                    .anyMatch(c -> c.keywordScore() > 0.0 || c.vectorScore() >= minVectorScore);
            if (!hasSignal) {
                return new Decision(true, "检索到的知识片段与问题相关性过低，依据不足，无法可靠回答该问题。");
            }
        }
        return new Decision(false, "");
    }

    public record Decision(boolean refused, String refusalMessage) {
    }
}
