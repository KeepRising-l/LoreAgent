package cn.keeprising.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TokenizerAndChunkerTest {

    private final Tokenizer tokenizer = new Tokenizer();
    private final MarkdownParser parser = new MarkdownParser();
    private final Chunker chunker = new Chunker();

    @Test
    void shouldTokenizePureChineseMixedChineseEnglishPunctuationAndEmpty() {
        assertThat(tokenizer.tokenize("向量检索")).containsExactly("向量", "量检", "检索");
        assertThat(tokenizer.tokenize("PostgreSQL 16配置pgvector"))
                .containsExactly("postgresql", "16", "配置", "pgvector");
        assertThat(tokenizer.tokenize("你好，世界！")).containsExactly("你好", "世界");
        assertThat(tokenizer.tokenize("")).isEmpty();
        assertThat(tokenizer.tokenize("   ！？...")).isEmpty();
    }

    @Test
    void shouldBuildOrTsQueryFilteringSingleCharStopwordsForLongChineseQuestion() {
        String query = "请问在 PostgreSQL 16 中如何配置向量检索的 HNSW 索引？";
        String tsQuery = tokenizer.toOrTsQuery(query);
        assertThat(tsQuery).contains(" | ");
        assertThat(tsQuery).doesNotContain("&");
        assertThat(tsQuery.split(" \\| ")).doesNotContain("在", "中", "的");
        assertThat(tsQuery).contains("postgresql", "16", "hnsw", "向量", "检索", "索引");
    }

    @Test
    void shouldParseAndChunkEvalCorpusIntoAtLeast100Chunks() throws Exception {
        Path corpusDir = Path.of("..", "eval", "corpus");
        if (!Files.isDirectory(corpusDir)) {
            corpusDir = Path.of("eval", "corpus");
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(corpusDir)) {
            files = s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
        assertThat(files).hasSizeBetween(6, 8);

        int totalChunks = 0;
        for (Path file : files) {
            String raw = Files.readString(file);
            assertThat(raw.length()).isGreaterThanOrEqualTo(2000);
            MarkdownParser.ParsedDocument doc = parser.parse(raw, file.getFileName().toString());
            List<Chunker.ChunkDraft> chunks = chunker.chunk(doc.sections());
            assertThat(chunks).isNotEmpty();
            totalChunks += chunks.size();
        }
        assertThat(totalChunks).isGreaterThanOrEqualTo(100);
    }
}
