package cn.keeprising.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 文本切片器：按段落边界与目标字符长度切块，保留相邻片间重叠窗口。
 * 这样既避免截断完整句子，又能让 6～8 篇技术笔记稳定切出 100+ 粒度适中的检索片段。
 */
public class Chunker {

    private static final int DEFAULT_MAX_CHARS = 240;
    private static final int DEFAULT_OVERLAP_CHARS = 40;

    private final int maxChunkChars;
    private final int overlapChars;

    public Chunker() {
        this(DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_CHARS);
    }

    public Chunker(int maxChunkChars, int overlapChars) {
        if (maxChunkChars <= 20) {
            throw new IllegalArgumentException("maxChunkChars 必须大于 20");
        }
        if (overlapChars < 0 || overlapChars >= maxChunkChars) {
            throw new IllegalArgumentException("overlapChars 必须位于 [0, maxChunkChars) 区间");
        }
        this.maxChunkChars = maxChunkChars;
        this.overlapChars = overlapChars;
    }

    public List<ChunkDraft> chunk(List<MarkdownParser.Section> sections) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        List<ChunkDraft> drafts = new ArrayList<>();
        int index = 0;
        for (MarkdownParser.Section section : sections) {
            String heading = section.heading() == null ? "" : section.heading().trim();
            List<String> pieces = splitParagraphsAndOversized(section.content());
            StringBuilder buf = new StringBuilder();
            for (String piece : pieces) {
                if (buf.isEmpty()) {
                    buf.append(piece);
                } else if (buf.length() + 2 + piece.length() <= maxChunkChars) {
                    buf.append("\n\n").append(piece);
                } else {
                    drafts.add(new ChunkDraft(index++, heading, buf.toString()));
                    buf.setLength(0);
                    buf.append(piece);
                }
            }
            if (!buf.isEmpty()) {
                drafts.add(new ChunkDraft(index++, heading, buf.toString()));
            }
        }
        return List.copyOf(drafts);
    }

    private List<String> splitParagraphsAndOversized(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        for (String para : content.split("\\n\\s*\\n")) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= maxChunkChars) {
                out.add(trimmed);
                continue;
            }
            // 超长段落按字符步长并尽量对齐句末标点切分，保留 overlapChars 重叠防止边界事实被切断
            int start = 0;
            while (start < trimmed.length()) {
                int idealEnd = Math.min(start + maxChunkChars, trimmed.length());
                int end = adjustToSentenceBoundary(trimmed, start, idealEnd);
                String slice = trimmed.substring(start, end).trim();
                if (!slice.isEmpty()) {
                    out.add(slice);
                }
                if (end >= trimmed.length()) {
                    break;
                }
                int nextStart = Math.max(start + 1, end - overlapChars);
                start = nextStart;
            }
        }
        return out;
    }

    private int adjustToSentenceBoundary(String text, int start, int idealEnd) {
        if (idealEnd >= text.length()) {
            return text.length();
        }
        int minSearch = start + Math.max(20, maxChunkChars / 2);
        for (int i = idealEnd; i >= minSearch; i--) {
            char ch = text.charAt(i - 1);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n' || ch == '.') {
                return i;
            }
        }
        return idealEnd;
    }

    public record ChunkDraft(int chunkIndex, String heading, String content) {
    }
}
