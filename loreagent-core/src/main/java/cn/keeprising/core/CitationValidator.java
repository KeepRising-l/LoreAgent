package cn.keeprising.core;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库引用校验器：统一校验模型输出中的 [K1]、[K2] 编号是否在本次检索候选中存在。
 * 只要出现越界或凭空编造的引用编号，直接抛出 Failure(INVALID_CITATION) 让整次运行失败。
 */
public class CitationValidator {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[K(\\d+)]");
    private static final int SNIPPET_MAX_LEN = 160;

    public List<Citation> validate(
            String answerText, List<RrfFusion.ScoredChunk> candidates, boolean allowUncited) {
        if (answerText == null) {
            return List.of();
        }
        int total = candidates == null ? 0 : candidates.size();
        Map<Integer, Citation> matched = new LinkedHashMap<>();
        Matcher matcher = CITATION_PATTERN.matcher(answerText);
        while (matcher.find()) {
            int idx = Integer.parseInt(matcher.group(1));
            if (idx < 1 || idx > total) {
                throw new Failure(Failure.ErrorCode.INVALID_CITATION,
                        "引用编号 [K" + idx + "] 超出本次有效范围 " + (total == 0 ? "(无候选片段)" : "[K1]-[K" + total + "]"));
            }
            matched.computeIfAbsent(idx, k -> toCitation(k, candidates.get(k - 1)));
        }

        boolean isRefusal = answerText.contains("依据不足");
        if (total > 0 && !allowUncited && !isRefusal && matched.isEmpty()) {
            throw new Failure(Failure.ErrorCode.INVALID_CITATION,
                    "模型基于知识库作答时未附带任何 [Kn] 引用编号，也未声明依据不足");
        }
        return List.copyOf(matched.values());
    }

    private Citation toCitation(int index, RrfFusion.ScoredChunk chunk) {
        String raw = chunk.content() == null ? "" : chunk.content().replaceAll("\\s+", " ").trim();
        String snippet = raw.length() <= SNIPPET_MAX_LEN ? raw : raw.substring(0, SNIPPET_MAX_LEN) + "...";
        return new Citation(
                "kb",
                "K" + index,
                chunk.documentId(),
                chunk.chunkId(),
                chunk.docTitle(),
                snippet);
    }

    public record Citation(
            String type,
            String refId,
            UUID documentId,
            long chunkId,
            String title,
            String snippet) {
    }
}
