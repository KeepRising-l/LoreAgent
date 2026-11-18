package cn.keeprising.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Markdown 解析器：提取可选 YAML Frontmatter（标题/日期/标签），并按标题层级切分章节。
 * 保留章节路径作为上下文标题，便于后续 Chunker 切片与引用展示时定位段落出处。
 */
public class MarkdownParser {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    public ParsedDocument parse(String rawMarkdown, String fallbackTitle) {
        if (rawMarkdown == null || rawMarkdown.isBlank()) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "文档内容不能为空");
        }
        String normalized = rawMarkdown.replace("\r\n", "\n");
        String title = fallbackTitle == null ? "" : fallbackTitle.trim();
        LocalDate docDate = null;
        List<String> tags = new ArrayList<>();

        String body = normalized;
        if (normalized.startsWith("---\n")) {
            int endIdx = normalized.indexOf("\n---\n", 4);
            if (endIdx > 0) {
                String fm = normalized.substring(4, endIdx);
                body = normalized.substring(endIdx + 5);
                for (String line : fm.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("title:") && title.isEmpty()) {
                        title = unquote(trimmed.substring(6));
                    } else if (trimmed.startsWith("date:")) {
                        docDate = tryParseDate(unquote(trimmed.substring(5)));
                    } else if (trimmed.startsWith("tags:")) {
                        tags.addAll(parseTags(trimmed.substring(5)));
                    }
                }
            }
        }

        List<Section> sections = new ArrayList<>();
        String currentHeading = "";
        StringBuilder currentBody = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            Matcher m = HEADING_PATTERN.matcher(line);
            if (m.matches()) {
                flushSection(sections, currentHeading, currentBody);
                currentHeading = m.group(2).trim();
                if (title.isEmpty()) {
                    title = currentHeading;
                }
            } else {
                if (!currentBody.isEmpty()) {
                    currentBody.append('\n');
                }
                currentBody.append(line);
            }
        }
        flushSection(sections, currentHeading, currentBody);
        if (sections.isEmpty()) {
            sections.add(new Section(title, body.trim()));
        }
        return new ParsedDocument(title, docDate, List.copyOf(tags), List.copyOf(sections));
    }

    private static void flushSection(List<Section> sections, String heading, StringBuilder body) {
        String text = body.toString().trim();
        if (!text.isEmpty()) {
            sections.add(new Section(heading, text));
        }
        body.setLength(0);
    }

    private static String unquote(String raw) {
        String t = raw.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private static LocalDate tryParseDate(String raw) {
        try {
            return raw.isBlank() ? null : LocalDate.parse(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> parseTags(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String part : cleaned.split(",")) {
            String tag = unquote(part);
            if (!tag.isBlank()) {
                out.add(tag);
            }
        }
        return out;
    }

    public record Section(String heading, String content) {
    }

    public record ParsedDocument(String title, LocalDate docDate, List<String> tags, List<Section> sections) {
    }
}
