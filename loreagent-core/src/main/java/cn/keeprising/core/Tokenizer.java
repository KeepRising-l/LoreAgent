package cn.keeprising.core;

import java.util.*;

/**
 * 中英混排轻量分词器：入库建索引与查询检索必须共用同一个类。
 * 中文按相邻字二元组（Bigram）切分（如 "向量检索" → 向量 / 量检 / 检索），
 * 英文与数字按连续词切分并转小写，跳过标点与空白；
 * 查询侧过滤单字虚词后用 OR (|) 拼成 PostgreSQL to_tsquery('simple', ...) 字符串，避开 plainto_tsquery 全词 AND 导致长问句返回空的坑。
 */
public class Tokenizer {

    private static final Set<String> SINGLE_CHAR_STOPWORDS = Set.of(
            "的", "了", "在", "是", "有", "和", "与", "或", "中", "里", "上", "下",
            "对", "把", "被", "给", "从", "到", "为", "以", "吗", "呢", "啊", "吧",
            "么", "这", "那", "我", "你", "他", "她", "它", "都", "也", "就", "可",
            "能", "会", "要", "想", "请", "问", "怎", "哪", "啥", "很", "太", "更",
            "并", "而", "及", "其", "之", "于", "着", "过", "得", "地", "不", "无"
    );

    // 切分出用于入库和查询的 token 序列（保留出现次数，便于 ts_rank 计算词频）
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        int len = text.length();
        int i = 0;
        while (i < len) {
            char c = text.charAt(i);
            if (isAsciiWordChar(c)) {
                int start = i;
                while (i < len && isAsciiWordChar(text.charAt(i))) {
                    i++;
                }
                tokens.add(text.substring(start, i).toLowerCase(Locale.ROOT));
            } else if (isCjk(c)) {
                int start = i;
                while (i < len && isCjk(text.charAt(i))) {
                    i++;
                }
                int runLen = i - start;
                if (runLen == 1) {
                    tokens.add(text.substring(start, i));
                } else {
                    for (int j = start; j < i - 1; j++) {
                        tokens.add(text.substring(j, j + 2));
                    }
                }
            } else {
                // 标点符号、空白及其他字符仅作为切分边界
                i++;
            }
        }
        return tokens;
    }

    // 入库 chunks.tokens 列：空格连接，配合 PostgreSQL to_tsvector('simple', tokens) GIN 索引
    public String toTsTokens(String text) {
        List<String> tokens = tokenize(text);
        return tokens.isEmpty() ? "" : String.join(" ", tokens);
    }

    // 查询侧：过滤单字停用词并去重后，用 | 拼成合法的 to_tsquery('simple', ...) 查询串
    public String toOrTsQuery(String query) {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token.length() == 1 && SINGLE_CHAR_STOPWORDS.contains(token)) {
                continue;
            }
            if (!token.isBlank()) {
                unique.add(token);
            }
        }
        // 若过滤停用词后全被去掉，保留非空单字兜底，避免合法短查询变空
        if (unique.isEmpty()) {
            return "";
        }
        return String.join(" | ", unique);
    }

    private static boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_';
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN;
    }
}
